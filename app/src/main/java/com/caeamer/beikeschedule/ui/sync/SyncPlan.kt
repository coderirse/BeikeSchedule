package com.caeamer.beikeschedule.ui.sync

/**
 * 一键同步的步骤（声明顺序即执行顺序）。
 *
 * 三条抓取脚本共用同一个 WebView 会话：先拿学号（身份），再换云 token 并决定
 * "云端数据怎么办"，最后才抓课表/成绩，必要时补一次上传。"从云端恢复"是唯一
 * 跳过抓取的分支——刚恢复就抓一遍会把云端数据又覆盖掉。
 */
enum class SyncStep(val label: String) {
    IDENTITY("获取学号"),
    CLOUD_TOKEN("登录云账号"),
    TIMETABLE("导入课表"),
    GRADES("抓取成绩"),
    BACKUP("上传云备份"),
    RESTORE("从云端恢复"),
}

enum class SyncStatus { OK, FAILED, SKIPPED }

data class SyncStepResult(
    val step: SyncStep,
    val status: SyncStatus,
    val message: String? = null,
)

/** 本轮同步对"云端数据"的处理方向（由云端探测 + 用户选择决定）。 */
enum class CloudMode {
    /** 还没探测（首轮进入时的起始值）。 */
    UNDECIDED,

    /** 打开同步开关并把本机数据上传（以本机为准）。 */
    UPLOAD,

    /** 只抓取，不开开关也不上传（用户选择"暂不上传"，或探测失败时的保守兜底）。 */
    NO_UPLOAD,

    /** 用云端数据覆盖本机，跳过抓取与上传。 */
    RESTORE,
}

/**
 * 步骤编排（纯逻辑，JVM 可单测）。
 *
 * 抽出来的原因：首轮执行的步骤序列依赖"云端决策"（RUN/跳过抓取），重试只跑失败项，
 * 这些规则一旦散在 ViewModel 的协程里就没法回归测试，而它们直接决定"会不会把用户
 * 本机/云端数据覆盖掉"。
 */
internal object SyncPlanner {

    fun steps(mode: CloudMode): List<SyncStep> = when (mode) {
        CloudMode.RESTORE -> listOf(SyncStep.IDENTITY, SyncStep.CLOUD_TOKEN, SyncStep.RESTORE)
        CloudMode.UPLOAD -> listOf(
            SyncStep.IDENTITY,
            SyncStep.CLOUD_TOKEN,
            SyncStep.TIMETABLE,
            SyncStep.GRADES,
            SyncStep.BACKUP,
        )
        // UNDECIDED 只出现在首轮前半段；按"不上传"取后续步骤，上传与否在决策后追加
        CloudMode.UNDECIDED,
        CloudMode.NO_UPLOAD,
        -> listOf(SyncStep.IDENTITY, SyncStep.CLOUD_TOKEN, SyncStep.TIMETABLE, SyncStep.GRADES)
    }

    /** 重试计划：只重跑未成功的步骤（跳过的步骤不再补跑）。 */
    fun retrySteps(mode: CloudMode, results: Map<SyncStep, SyncStepResult>): List<SyncStep> =
        steps(mode).filter { results[it]?.status != SyncStatus.OK }
}

/**
 * 云端探测结果 → 默认处理方向。
 *
 * - 探测失败：保守当"不上传"（宁可不备份，也不能拿本机数据覆盖云端那份可能更全的备份）
 * - 云端没有备份：本机上传（首次登录）
 * - 云端有备份但本机没数据：直接恢复（换机/重装场景）
 * - 两边都有数据：留给用户三选一
 */
internal fun defaultCloudMode(
    probeFailed: Boolean,
    cloudHasBackup: Boolean,
    localHasData: Boolean,
): CloudMode = when {
    probeFailed -> CloudMode.NO_UPLOAD
    !cloudHasBackup -> CloudMode.UPLOAD
    !localHasData -> CloudMode.RESTORE
    else -> CloudMode.UNDECIDED
}

/** 是否需要弹"云端已有备份"三选一。 */
internal fun needsCloudDecision(mode: CloudMode): Boolean = mode == CloudMode.UNDECIDED
