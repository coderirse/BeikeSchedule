package com.caeamer.beikeschedule.data.backup

import android.content.Context
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.remote.CloudApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 云同步编排：整包快照的上传/下载 + 脏标记 + 去抖自动备份。
 *
 * **为什么是"脏标记 + 机会式上传"而不是实时推送**：改动点散落在课程/日程/成绩各处，
 * 每次改动都立即上传既费流量又容易与用户连续操作打架。这里只做三件事：
 * 1. 改动点调 [markDirty]：落一个 DataStore 脏标记（进程被杀也不丢），若云同步已开启
 *    且已登录，再排一个 8 秒去抖上传——连续编辑只会上传最后一次状态；
 * 2. App 启动时调 [uploadIfDirty]：补传上次进程死亡漏掉的上传；
 * 3. 「我的」页的"立即备份"/"从云端恢复"直接调 [backupNow] / [restore]。
 *
 * 冲突语义：整包快照，后写覆盖（谁备份谁生效），恢复前由 UI 弹窗明示"将覆盖本机数据"。
 */
object CloudSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadMutex = Mutex()

    @Volatile private var appContext: Context? = null
    private var debounceJob: Job? = null

    /** 数据变更点调用：落脏标记；已开启云同步且已登录时排去抖上传。 */
    fun markDirty(context: Context) {
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val settings = SettingsStore(app)
            settings.markCloudDirty()
            val account = settings.cloudAccount.first()
            if (account.isLoggedIn && settings.cloudSyncEnabled.first()) scheduleDebouncedUpload(app)
        }
    }

    /** App 启动时调用：把上次进程死亡漏掉的脏标记补传掉。 */
    fun uploadIfDirty(context: Context) {
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val settings = SettingsStore(app)
            val account = settings.cloudAccount.first()
            if (account.isLoggedIn && settings.cloudSyncEnabled.first() && settings.cloudDirty.first()) {
                runCatching { backupNow(app) }
            }
        }
    }

    private fun scheduleDebouncedUpload(app: Context) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching { backupNow(app) }
        }
    }

    /**
     * 自动整包备份（有脏标记才上传）。返回 null 表示本次没有上传（未登录 / 未开启 / 没有改动）。
     */
    suspend fun backupNow(context: Context): Result<Unit>? = uploadIfChanged(context, force = false)

    /** 手动"立即备份"：无论脏标记状态都构建并上传一次。 */
    suspend fun manualBackup(context: Context): Result<Unit> =
        uploadIfChanged(context, force = true) ?: Result.failure(IllegalStateException("未登录云账号"))

    private suspend fun uploadIfChanged(context: Context, force: Boolean): Result<Unit>? =
        uploadMutex.withLock {
            val app = context.applicationContext
            appContext = app
            val settings = SettingsStore(app)
            val account = settings.cloudAccount.first()
            if (!account.isLoggedIn) return@withLock null
            // 手动备份绕过脏标记（用户要求刚才的改动立刻上云）；自动上传必须脏
            if (!settings.cloudDirty.first() && !force) return@withLock null

            try {
                val snapshot = CloudSnapshotCodec.build(app)
                val encoded = CloudSnapshot.encode(snapshot)
                CloudApi.putBackup(account.token, encoded, appVersionCode(app))
                settings.clearCloudDirty()
                settings.setCloudLastBackupAt(System.currentTimeMillis())
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 从云端整包恢复。成功返回快照，失败抛异常（未备份/网络/格式）由 UI 转文案。
     * 恢复成功后本机数据与云端一致，清脏标记并记录备份时间（内容即云端快照）。
     */
    suspend fun restore(context: Context): CloudSnapshot = uploadMutex.withLock {
        val app = context.applicationContext
        appContext = app
        val settings = SettingsStore(app)
        val account = settings.cloudAccount.first()
        if (!account.isLoggedIn) throw IllegalStateException("未登录云账号")

        val envelope = CloudApi.getBackup(account.token)
        if (!envelope.exists || envelope.data == null) {
            throw IllegalStateException("云端还没有备份")
        }
        val snapshot = CloudSnapshot.decode(envelope.data.toString())
        CloudSnapshotCodec.applyRestore(app, snapshot)
        settings.clearCloudDirty()
        settings.setCloudLastBackupAt(envelope.updatedAt)
        snapshot
    }

    private fun appVersionCode(context: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
        }
    }.getOrDefault(0)

    private const val DEBOUNCE_MS = 8_000L
}
