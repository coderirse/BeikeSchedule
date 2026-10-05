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

    /**
     * 前半段：身份 + 云账号。云账号步骤内含云端探测与方向决策，所以后半段只能在它跑完之后
     * 按**当时**的 mode 算出来——首轮与重试都必须分两段，不能一次排完整张表。
     */
    val head: List<SyncStep> = listOf(SyncStep.IDENTITY, SyncStep.CLOUD_TOKEN)

    fun steps(mode: CloudMode): List<SyncStep> = head + tail(mode)

    /** 后半段（方向已定）。UNDECIDED 只出现在首轮前半段，按"不上传"取。 */
    fun tail(mode: CloudMode): List<SyncStep> = when (mode) {
        CloudMode.RESTORE -> listOf(SyncStep.RESTORE)
        CloudMode.UPLOAD -> listOf(SyncStep.TIMETABLE, SyncStep.GRADES, SyncStep.BACKUP)
        CloudMode.UNDECIDED,
        CloudMode.NO_UPLOAD,
        -> listOf(SyncStep.TIMETABLE, SyncStep.GRADES)
    }

    /** 重试的前半段：只重跑未成功的身份/云账号步骤。 */
    fun retryHead(results: Map<SyncStep, SyncStepResult>): List<SyncStep> =
        head.filter { results[it]?.status != SyncStatus.OK }

    /**
     * 重试的后半段：按**当前** mode 取未成功的步骤（跳过的步骤不再补跑）。
     *
     * 必须在 head 之后现算：首轮 CLOUD_TOKEN 失败时 mode 还是 UNDECIDED，重试跑完 head 才
     * 决策出 UPLOAD/RESTORE。若照 UNDECIDED 的表重试，云端那一半（上传或恢复）永远不会补跑——
     * 用户明确选了"从云端恢复"却只抓到本机数据，还会被提示"将上传本机数据"。
     */
    fun retryTail(mode: CloudMode, results: Map<SyncStep, SyncStepResult>): List<SyncStep> =
        tail(mode).filter { results[it]?.status != SyncStatus.OK }
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
