package com.caeamer.beikeschedule.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.backup.CloudSync
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.remote.CloudApi
import com.caeamer.beikeschedule.data.remote.CloudAuthException
import com.caeamer.beikeschedule.data.remote.UpdateSignature
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.parser.GradesParser
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** 更新检查状态。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(
        val latestVersion: String,
        val notes: String,
        val url: String,
        /** 服务端 force 标记：弹窗不可跳过（仅自有服务器接口提供，GitHub 兜底恒为 false）。 */
        val force: Boolean = false,
        /**
         * APK 的 SHA-256（hex 小写）。**只在签名覆盖它时非空**（自有源新约定）；
         * 非空且 url 是直链 APK 时走应用内下载 + 摘要校验 + 安装，
         * 否则保持浏览器打开的旧链路（依赖系统同签名检查兜底）。
         */
        val apkSha256: String = "",
    ) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val themeMode: StateFlow<SettingsStore.ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.ThemeMode.SYSTEM)
    val update: StateFlow<UpdateState> = updateState
    val studentProfile: StateFlow<SettingsStore.StudentProfile> = settings.studentProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.StudentProfile())
    val appVersion: StateFlow<String> = MutableStateFlow(
        runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: ""
        }.getOrDefault(""),
    )

    /** 「隐藏本周不上的课」：与课表页共用同一个 DataStore 键，两边即时同步。 */
    val hideInactiveCourses: StateFlow<Boolean> = settings.hideInactiveCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // —— 云同步（账号 = 学号，opt-in 默认关闭）——

    val cloudAccount: StateFlow<SettingsStore.CloudAccount> = settings.cloudAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.CloudAccount())
    val cloudSyncEnabled: StateFlow<Boolean> = settings.cloudSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val cloudLastBackupAt: StateFlow<Long> = settings.cloudLastBackupAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** 手动备份/恢复进行中（UI 据此禁用按钮并显示进度）。 */
    val cloudBusy = MutableStateFlow(false)

    /** 一次性结果提示（UI Toast 后调 consumeCloudEvent 清空）。 */
    val cloudEvent = MutableStateFlow<String?>(null)

    fun setCloudSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setCloudSyncEnabled(enabled)
            // 开启即做一次全量备份：用户"同意上云"的动作应当立刻看到云端有数据，
            // 而不是等下一次数据变更的 8 秒去抖
            if (enabled && settings.cloudAccount.first().isLoggedIn) {
                val result = CloudSync.manualBackup(getApplication())
                cloudEvent.value = result.fold(
                    onSuccess = { "已备份到云端" },
                    onFailure = { "首次备份失败：${it.message}" },
                )
            }
        }
    }

    fun backupNow() {
        if (cloudBusy.value) return
        cloudBusy.value = true
        viewModelScope.launch {
            val result = CloudSync.manualBackup(getApplication())
            cloudEvent.value = result.fold(
                onSuccess = { "已备份到云端" },
                onFailure = { e ->
                    if (e.isAuthExpired()) {
                        // CloudSync 已清 token；这里只负责文案
                        "登录已过期，请重新登录云账号"
                    } else {
                        "备份失败：${e.message}"
                    }
                },
            )
            cloudBusy.value = false
        }
    }

    fun restoreFromCloud() {
        if (cloudBusy.value) return
        cloudBusy.value = true
        viewModelScope.launch {
            try {
                CloudSync.restore(getApplication())
                cloudEvent.value = "已从云端恢复全部数据"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isAuthExpired()) {
                    settings.clearCloudAccount()
                    cloudEvent.value = "登录已过期，请重新登录云账号"
                } else {
                    cloudEvent.value = "恢复失败：${e.message}"
                }
            }
            cloudBusy.value = false
        }
    }

    /** 退出云账号：只清本机 token，云端备份保留（换设备重新教务登录即可找回）。 */
    fun logoutCloud() {
        viewModelScope.launch { settings.clearCloudAccount() }
    }

    fun consumeCloudEvent() {
        cloudEvent.value = null
    }

    fun setHideInactiveCourses(hidden: Boolean) {
        viewModelScope.launch { settings.setHideInactiveCourses(hidden) }
    }

    init {
        checkUpdate()
    }

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** 清除成绩本地缓存（含考试安排与学业进度，下次进教务 Tab 重新抓取）。 */
    fun clearGradesCache() {
        viewModelScope.launch {
            val repo = ScheduleRepository(getApplication())
            settings.saveGradesMeta("", 0L)
            settings.saveCreditMeta("", "")
            repo.replaceGrades(emptyList())
            repo.replaceExams(emptyList())
            // 考试数据已清空 → 同步取消已排的考前提醒
            // （否则成绩清完了，旧的"明天考试"闹钟还会带着地点/座位号弹出来）。
            // cancelDueAlarms = true：用户显式清空，连"已到点但系统还没投递"的那条
            // 也不要再弹；日常重排必须保持默认 false，否则会丢掉 Doze 下未投递的提醒。
            // runCatching：重排异常逃出 viewModelScope 会崩进程，这里只允许"本轮不重排"。
            runCatching { ExamReminderScheduler.reschedule(getApplication(), cancelDueAlarms = true) }
                .onFailure { e -> if (e is CancellationException) throw e }
        }
    }

    /**
     * 检查最新版本（进入设置页自动触发，可手动重查）。
     * 优先自有服务器（国内可达、支持 force 强更与直链 APK）；服务器失败时回退 GitHub Releases。
     */
    fun checkUpdate() {
        if (updateState.value is UpdateState.Checking) return
        updateState.value = UpdateState.Checking
        viewModelScope.launch {
            val fromServer = runCatching { fetchLatestFromServer() }
                .getOrElse { if (it is CancellationException) throw it else null }
            updateState.value = fromServer ?: fetchLatestRelease()
        }
    }

    /**
     * 自有服务器：GET /api/bs/app/latest，按 versionCode 数值比较。
     *
     * **无签名 / 验签失败 → 返回 null 完全忽略自有源**（回退 GitHub）。
     * 明文 HTTP 下 force + APK URL 可被 MITM 改写，未验签的元数据不得驱动更新。
     */
    private suspend fun fetchLatestFromServer(): UpdateState? = withContext(Dispatchers.IO) {
        val installed = installedVersionCode() ?: return@withContext null
        val latest = CloudApi.latestVersion()
        if (latest.versionCode <= 0 || latest.versionName.isBlank()) return@withContext null
        // 验签失败 → 整体作废回退 GitHub；旧约定（签名不含 APK 摘要）仍可用，
        // 但响应里的 apkSha256 不在签名内、必须当不存在处理
        val coverage = UpdateSignature.verifyDetailed(latest) ?: return@withContext null
        val trustedSha256 = if (coverage == UpdateSignature.SignatureCoverage.FULL) latest.apkSha256 else ""
        if (latest.versionCode > installed) {
            UpdateState.Available(
                latest.versionName, latest.changelog, latest.url, latest.force, trustedSha256,
            )
        } else {
            UpdateState.UpToDate
        }
    }

    private fun installedVersionCode(): Int? = runCatching {
        val info = getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }.getOrNull()

    private suspend fun fetchLatestRelease(): UpdateState = withContext(Dispatchers.IO) {
        val installed = runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0).versionName
        }.getOrNull()
            ?: return@withContext UpdateState.Failed("无法读取本机版本号")

        // disconnect 必须在 finally：此前只有成功路径会断开，非 200 早退与异常路径
        // 都泄漏连接直到 GC（弱网下表现为后续请求排队变慢）。
        var conn: HttpURLConnection? = null
        try {
            conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "BeikeSchedule")
            if (conn.responseCode != 200) {
                return@withContext UpdateState.Failed("GitHub 请求失败（HTTP ${conn.responseCode}）")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = Json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return@withContext UpdateState.Failed("响应缺少版本号")
            // body 可能是 JSON null：jsonPrimitive.content 对字面量 null 会返回字符串 "null"，
            // 直接进更新说明会显示"null"。显式判空。
            val notes = obj["body"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content.orEmpty().take(300)
            val url = obj["html_url"]?.jsonPrimitive?.content ?: REPO_URL
            when {
                GradesParser.compareVersions(tag, installed) > 0 ->
                    UpdateState.Available(tag.removePrefix("v"), notes, url)
                else -> UpdateState.UpToDate
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            UpdateState.Failed("检查失败：${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        /** 仓库主页（"我的"页 GitHub 入口与更新检查共用）。 */
        const val REPO_URL = "https://github.com/coderirse/BeikeSchedule"
        private const val RELEASES_API = "https://api.github.com/repos/coderirse/BeikeSchedule/releases/latest"

        /** 外部系统入口（"我的"页外链组）。课程平台/实践平台地址待补后追加。 */
        const val PINGJIAO_URL = "https://pingjiao.ustb.edu.cn"
        const val SRTP_URL = "https://srtp.ustb.edu.cn"
    }
}

/** 异常是否为云 token 失效（含被包装一层的情况）。 */
internal fun Throwable.isAuthExpired(): Boolean =
    generateSequence(this) { it.cause }.any { it is CloudAuthException }
