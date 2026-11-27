package com.caeamer.beikeschedule.data.backup

import android.content.Context
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.remote.BackupSignature
import com.caeamer.beikeschedule.data.remote.CloudApi
import com.caeamer.beikeschedule.data.remote.CloudAuthException
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 云同步编排：整包快照的上传/下载 + 脏标记 + 去抖自动备份。
 *
 * **为什么是"脏标记 + 机会式上传"而不是实时推送**：改动点散落在课程/日程/成绩各处，
 * 每次改动都立即上传既费流量又容易与用户连续操作打架。这里只做三件事：
 * 1. 改动点调 [markDirty]：云同步开启时落一个 DataStore 脏标记（进程被杀也不丢），
 *    已登录再排一个 8 秒去抖上传——连续编辑只会上传最后一次状态；
 * 2. App 启动时调 [uploadIfDirty]：补传上次进程死亡漏掉的上传；
 * 3. 「我的」页的"立即备份"/"从云端恢复"直接调 [manualBackup] / [restore]。
 *
 * 冲突语义：整包快照，后写覆盖（谁备份谁生效），恢复前由 UI 弹窗明示"将覆盖本机数据"。
 *
 * **并发模型**：脏标记簿记（markDirty / 去抖 Job）收敛在单线程调度器上串行执行，
 * debounceJob 的 cancel/赋值因此无竞态；上传与恢复仍由 [uploadMutex] 全局互斥。
 * 需要"写本机但不触发上传"的调用方（云恢复、清除成绩缓存）走 [withoutDirtyMarking]。
 */
object CloudSync {

    // 簿记单线程化：markDirty 从多线程写入点调用，去抖 Job 的换手必须有序
    private val bookDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + bookDispatcher)
    private val uploadMutex = Mutex()

    private var debounceJob: Job? = null

    /** 同步和恢复内部可以显式备份，且它们自己的本机写入不触发自动上传。 */
    private object LocalWrite : AbstractCoroutineContextElement(Key) {
        object Key : CoroutineContext.Key<LocalWrite>
    }

    suspend fun markDirty(context: Context) {
        if (currentCoroutineContext()[LocalWrite.Key] != null) return
        val app = context.applicationContext
        scope.launch {
            val settings = SettingsStore(app)
            if (settings.cloudBackupState.first().automaticAllowed && settings.markCloudDirty()) {
                scheduleDebouncedUpload(app)
            }
        }
    }

    /** App 启动时补传已开启且未暂缓的待同步改动。 */
    fun uploadIfDirty(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { backupNow(app) } }
    }

    private fun scheduleDebouncedUpload(app: Context) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            // 只去抖排队中的任务。已经开始的上传用变更代数确认，不因用户再次编辑而丢失应答。
            debounceJob = null
            runCatching { backupNow(app) }
        }
    }

    /** 等正在执行的上传结束，再运行整段本机写入；写入期间不会有后台任务读取半成品。 */
    suspend fun <T> withoutDirtyMarking(block: suspend () -> T): T = withUploadLock {
        if (currentCoroutineContext()[LocalWrite.Key] != null) return@withUploadLock block()
        withContext(bookDispatcher) {
            debounceJob?.cancel()
            debounceJob = null
        }
        withContext(LocalWrite) { block() }
    }

    private suspend fun <T> withUploadLock(block: suspend () -> T): T =
        if (currentCoroutineContext()[LocalWrite.Key] != null) block() else uploadMutex.withLock { block() }

    private suspend fun backupNow(context: Context): Result<Unit>? = uploadIfChanged(context, force = false)

    /** 显式备份可以上传暂缓的数据，成功后恢复按开关自动备份。 */
    suspend fun manualBackup(context: Context): Result<Unit> =
        uploadIfChanged(context, force = true) ?: Result.failure(IllegalStateException("未登录云账号"))

    internal suspend fun uploadIfChanged(
        context: Context,
        force: Boolean,
        putBackup: suspend (String, String, Int) -> Long = CloudApi::putBackup,
    ): Result<Unit>? = withUploadLock {
        val app = context.applicationContext
        val settings = SettingsStore(app)
        val initial = settings.cloudBackupState.first()
        if (!force && (!initial.automaticAllowed || !initial.dirty)) return@withUploadLock null
        val account = settings.cloudAccount.first()
        if (!account.isLoggedIn) return@withUploadLock null

        var uploaded = false
        repeat(MAX_REBUILD_ROUNDS) {
            try {
                val pending = settings.cloudBackupState.first()
                val explicitlyRequested = force && !uploaded
                if (pending.accountId != account.xh || (!explicitlyRequested && (!pending.automaticAllowed || !pending.dirty))) {
                    return@withUploadLock if (uploaded) Result.success(Unit) else null
                }

                val snapshot = CloudSnapshotCodec.build(app)
                val encoded = CloudSnapshot.encode(snapshot)
                val latest = settings.cloudBackupState.first()
                currentCoroutineContext().ensureActive()
                if (latest.accountId != account.xh || (!explicitlyRequested && !latest.automaticAllowed)) {
                    return@withUploadLock if (uploaded) Result.success(Unit) else null
                }

                val revision = putBackup(account.token, encoded, appVersionCode(app))
                uploaded = true
                val confirmed = settings.confirmCloudBackup(account.xh, pending.generation, revision, explicitlyRequested)
                if (!confirmed.dirty || !confirmed.automaticAllowed) return@withUploadLock Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CloudAuthException && settings.cloudBackupState.first().accountId == account.xh) {
                    runCatching { settings.clearCloudAccount() }
                }
                return@withUploadLock Result.failure(e)
            }
        }
        Result.success(Unit)
    }

    /**
     * 从云端整包恢复。成功返回快照，失败抛异常（未备份/网络/格式）由 UI 转文案。
     *
     * **回滚点**：恢复前把本机当前数据整包导出到 filesDir/cloud-restore-rollback.json
     * （尽力而为，失败不阻断恢复）。恢复的 Room 部分是单事务（全成全败），但 DataStore
     * 无多键事务，进程中途被杀会留下"课程已换、设置半新半旧"；此时该文件就是最后一份
     * 完整本机数据。恢复成功后即覆盖写下一轮回滚点，不清理。
     *
     * 恢复全程走 [withoutDirtyMarking]：写十几个设置键不该触发上传，恢复完成再清一次脏标记。
     */
    suspend fun restore(context: Context): CloudSnapshot = withoutDirtyMarking {
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
            settings.pauseCloudAutoBackup()
            CloudSnapshotCodec.applyRestore(app, snapshot)
            settings.confirmCloudBackup(account.xh, generation = null, revision = envelope.updatedAt, resumeAutomatic = true)
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
