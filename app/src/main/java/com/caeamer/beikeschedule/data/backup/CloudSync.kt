package com.caeamer.beikeschedule.data.backup

import android.content.Context
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.remote.BackupSignature
import com.caeamer.beikeschedule.data.remote.CloudApi
import com.caeamer.beikeschedule.data.remote.CloudAuthException
import java.io.File
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
 *
 * **并发模型**：脏标记簿记（markDirty / 去抖 Job）收敛在单线程调度器上串行执行，
 * debounceJob 的 cancel/赋值因此无竞态；上传与恢复仍由 [uploadMutex] 全局互斥。
 * 恢复期间用 [suppressDirty] 抑制设置写入点的自动置脏（否则恢复写十几个键会立刻
 * 把刚清掉的脏标记又打上，触发一次冗余整包上传）。
 */
object CloudSync {

    // 簿记单线程化：markDirty 从多线程写入点调用，去抖 Job 的换手必须有序
    private val bookDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + bookDispatcher)
    private val uploadMutex = Mutex()

    private var debounceJob: Job? = null

    /** 恢复期间为 true：applyRestore 写的十几个设置键不应触发自动置脏。 */
    @Volatile
    private var suppressDirty = false

    /** 数据变更点调用：落脏标记；已开启云同步且已登录时排去抖上传。 */
    fun markDirty(context: Context) {
        if (suppressDirty) return
        val app = context.applicationContext
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

    /**
     * 构建快照 → 上传 → 清脏标记。
     *
     * **撕裂防护**：快照构建需要上百毫秒，期间若发生并发写入（成绩抓取落库、用户编辑日程），
     * 本轮快照就不含最新改动。因此上传成功后复查脏标记：仍为脏说明构建期间有新写入，
     * 重建再传（最多 3 轮；轮次耗尽仍脏则保留脏标记，交给下一个去抖周期补传，绝不
     * 把"没包含最新改动"的状态标成已同步）。
     */
    private suspend fun uploadIfChanged(context: Context, force: Boolean): Result<Unit>? =
        uploadMutex.withLock {
            val app = context.applicationContext
            val settings = SettingsStore(app)
            val account = settings.cloudAccount.first()
            if (!account.isLoggedIn) return@withLock null
            // 手动备份绕过脏标记（用户要求刚才的改动立刻上云）；自动上传必须脏
            if (!settings.cloudDirty.first() && !force) return@withLock null

            var attempt = 0
            while (true) {
                attempt++
                try {
                    val snapshot = CloudSnapshotCodec.build(app)
                    val encoded = CloudSnapshot.encode(snapshot)
                    CloudApi.putBackup(account.token, encoded, appVersionCode(app))
                    val stillDirty = settings.cloudDirty.first()
                    if (!stillDirty || attempt >= MAX_REBUILD_ROUNDS) {
                        // 轮次耗尽仍脏：不 clear（最新改动留给下个去抖周期），本次内容已上云
                        if (!stillDirty) settings.clearCloudDirty()
                        settings.setCloudLastBackupAt(System.currentTimeMillis())
                        return@withLock Result.success(Unit)
                    }
                    // stillDirty：构建期间有新写入，重建再传
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // token 失效：清本地登录态，自动同步停下，避免静默反复失败
                    if (e is CloudAuthException) {
                        runCatching { settings.clearCloudAccount() }
                    }
                    return@withLock Result.failure(e)
                }
            }
            error("unreachable") // while(true) 无正常出口，所有路径都在循环内 return
        }

    /**
     * 从云端整包恢复。成功返回快照，失败抛异常（未备份/网络/格式）由 UI 转文案。
     *
     * **回滚点**：恢复前把本机当前数据整包导出到 filesDir/cloud-restore-rollback.json
     * （尽力而为，失败不阻断恢复）。恢复的 Room 部分是单事务（全成全败），但 DataStore
     * 无多键事务，进程中途被杀会留下"课程已换、设置半新半旧"；此时该文件就是最后一份
     * 完整本机数据。恢复成功后即覆盖写下一轮回滚点，不清理。
     *
     * 恢复期间 [suppressDirty] 抑制设置写入点的自动置脏，恢复完成清一次脏标记。
     */
    suspend fun restore(context: Context): CloudSnapshot = uploadMutex.withLock {
        val app = context.applicationContext
        val settings = SettingsStore(app)
        val account = settings.cloudAccount.first()
        if (!account.isLoggedIn) throw IllegalStateException("未登录云账号")

        try {
            val envelope = CloudApi.getBackup(account.token)
            if (!envelope.exists || envelope.data == null) {
                throw IllegalStateException("云端还没有备份")
            }
            // 完整性强制验签：明文 HTTP 上快照可被整包替换，恢复又是整表覆盖——
            // 无签名/验签失败一律拒绝（恢复流程宁可失败，也不把投毒快照写进本机）
            val snapshotText = envelope.snapshot
            if (snapshotText.isNullOrBlank() ||
                !BackupSignature.verify(snapshotText, envelope.sig)
            ) {
                throw java.io.IOException("云端备份校验失败，已拒绝恢复（请更新服务器或重新备份一次）")
            }
            val snapshot = CloudSnapshot.decode(snapshotText)
            saveRollbackPoint(app)
            suppressDirty = true
            try {
                CloudSnapshotCodec.applyRestore(app, snapshot)
                settings.clearCloudDirty()
                settings.setCloudLastBackupAt(envelope.updatedAt)
            } finally {
                suppressDirty = false
            }
            snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CloudAuthException) {
                runCatching { settings.clearCloudAccount() }
            }
            throw e
        }
    }

    /** 恢复前导出本机当前状态（尽力而为）。 */
    private suspend fun saveRollbackPoint(app: Context) {
        runCatching {
            val snapshot = CloudSnapshotCodec.build(app)
            File(app.filesDir, ROLLBACK_FILE).writeText(CloudSnapshot.encode(snapshot))
        }
    }

    private fun appVersionCode(context: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
        }
    }.getOrDefault(0)

    private const val DEBOUNCE_MS = 8_000L
    private const val MAX_REBUILD_ROUNDS = 3
    private const val ROLLBACK_FILE = "cloud-restore-rollback.json"
}
