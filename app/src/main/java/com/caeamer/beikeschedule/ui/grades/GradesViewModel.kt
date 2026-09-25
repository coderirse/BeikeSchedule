package com.caeamer.beikeschedule.ui.grades

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.pref.ScorePrivacy
import com.caeamer.beikeschedule.data.repo.CreditAggregator
import com.caeamer.beikeschedule.data.repo.GpaCalculator
import com.caeamer.beikeschedule.data.repo.GradeRows
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.data.repo.WeightedScoreCalculator
import com.caeamer.beikeschedule.import.parser.CreditProgressParser
import com.caeamer.beikeschedule.import.parser.CreditProgressParser.CreditCategory
import com.caeamer.beikeschedule.import.parser.CreditProgressParser.GraduationProgress
import com.caeamer.beikeschedule.import.parser.ExamsParser
import com.caeamer.beikeschedule.import.parser.GradesParser
import com.caeamer.beikeschedule.import.parser.GpaInfo
import com.caeamer.beikeschedule.import.parser.JwParser
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import com.caeamer.beikeschedule.reminder.TodoReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 成绩展示模式：加权（默认，只看必修数字成绩）/ GPA（教务官方值）。 */
enum class ScoreMode { WEIGHTED, GPA }

/**
 * 教务 Tab 的分段。
 *
 * 顺序即界面顺序，`ordinal` 直接用于持久化：**0=无课教室（默认）**、1=日程、2=成绩、3=考试。
 * 用户明确要求"默认打开教务是无课教室"——成绩使用率不高，放第一位会让人每次都要切。
 *
 * 注意：枚举顺序即持久化序号。本次在成绩前插入"日程"，老用户存的旧序号（1=成绩/2=考试）
 * 升级后会一次性落到相邻分段，之后正常；因分段是低频偏好且立刻可改回，不做序号映射迁移。
 */
enum class GradesSection { FREE_ROOM, TODO, SCORES, EXAMS }

/** 学分进度行：类别要求（接口）+ 本地已完成（成绩汇总）。 */
data class CreditRow(val category: CreditCategory, val completed: Double)

data class GradesUiState(
    /** null = 分段偏好还没从 DataStore 读到（冷启动最初几帧），此时不渲染任何分段页。 */
    val section: GradesSection? = null,
    val grades: List<GradeEntity> = emptyList(),
    val exams: List<ExamEntity> = emptyList(),
    val gpa: GpaInfo? = null,
    val fetchedAt: Long = 0L,
    val error: String? = null,
    val scoreMode: ScoreMode = ScoreMode.WEIGHTED,
    /** 学期筛选：空=全部学期。 */
    val semesterFilter: String = "",
    /** 学年筛选：空=全部；值如 "2024-2025"（含该学年1/2学期）；"-3" 代表小学期单独一组。 */
    val schoolYearFilter: String = "",
    /** 用户手动排除出加权计算的课程代码集合。 */
    val excludedKcdm: Set<String> = emptySet(),
    /** 学分类别要求（queryXflbyq）。 */
    val creditCategories: List<CreditCategory> = emptyList(),
    /** 毕业总进度（queryBxkqk）。 */
    val gradProgress: GraduationProgress? = null,
    /** 成绩隐私：加权/GPA 大数字与成绩行分数默认隐藏，点小眼睛切换显示（会话级，退后台即复位）。 */
    val hideScores: Boolean = true,
) {
    /**
     * 每门课收敛后的行（补考/重修覆盖正考，见 [GradeRows.bestPerCourse]）。
     *
     * 全部派生值都必须基于它，而不是原始 `grades`——否则同一门课的补考+正考两行会
     * 被重复计入加权学分、未通过门数与学分类别进度。
     *
     * 用 `by lazy` 而非 `get()`：这些属性此前每次读取都重算，而组合期一次 `ScoreCard`
     * 就会读 `weightedResult` 2 次、`weightEligible` 3 次、`localGpa` 3 次；更糟的是
     * `failedBySemester` 在**每个学期分组项内部**被读（学期数 × 全量 filter+groupBy）。
     * `LazyThreadSafetyMode.NONE`：实例只在主线程被 Compose 读取，无需同步开销。
     */
    private val bestRows: List<GradeEntity> by lazy(LazyThreadSafetyMode.NONE) {
        GradeRows.bestPerCourse(grades)
    }

    /** 本地 4.0 制 GPA：全部有数字成绩的课程，补考/重修覆盖正考（教务网 BL 是平均学分绩/20 口径，不可用）。 */
    val localGpa: GpaCalculator.GpaResult? by lazy(LazyThreadSafetyMode.NONE) {
        GpaCalculator.calculate(bestRows)
    }

    /** 按学期分组（学期名倒序，学期内按原始顺序）。 */
    val grouped: List<Pair<String, List<GradeEntity>>> by lazy(LazyThreadSafetyMode.NONE) {
        grades.groupBy { it.xnxqmc }.toSortedMap(compareByDescending { it }).map { (k, v) -> k to v }
    }

    /**
     * 每学期挂科门数（学期名 → N）。
     *
     * 按**收敛后的课程**计数而非原始行：补考通过后正考的挂科行仍留在成绩单里，
     * 按行计数会让"N 门未通过"永不消失（与 docs/FEATURES_V1.1_DESIGN.md 的
     * "补考通过后自然消失"相矛盾）。
     */
    val failedBySemester: Map<String, Int> by lazy(LazyThreadSafetyMode.NONE) {
        bestRows.filter { it.isFailed }.groupBy { it.xnxqmc }.mapValues { it.value.size }
    }

    /**
     * 补考/重修已通过的课程代码。
     *
     * 成绩列表仍按原始行渲染（用户需要看到"正考 55 / 补考 75"两行），但这些行的
     * 挂科标红要按收敛结果决定，否则补考通过后正考行仍然标红。
     */
    val passedKcdm: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        bestRows.filter { it.isPassed }.map { it.kcdm }.toSet()
    }

    /** 本地按课程类别汇总的已通过学分（与教务网页口径一致）。 */
    val localCategorySums: Map<String, Double> by lazy(LazyThreadSafetyMode.NONE) {
        CreditAggregator.sumPassedByCategory(grades)
    }

    /** 学分进度行：要求（接口）× 已完成（本地汇总）。 */
    val creditRows: List<CreditRow> by lazy(LazyThreadSafetyMode.NONE) {
        val sums = localCategorySums
        creditCategories.map { CreditRow(it, CreditAggregator.completedCreditsFor(sums, it.kclbmc)) }
    }

    /** 考试按日期升序（无日期的排最后，按原文排序）。 */
    val examsSorted: List<ExamEntity> by lazy(LazyThreadSafetyMode.NONE) {
        exams.sortedWith(
            compareByDescending<ExamEntity> { it.hasDate }
                .thenBy { it.ksrq }
                .thenBy { it.kssj }
                .thenBy { it.kssjms },
        )
    }

    /** 去重后的学期列表（用于筛选）。 */
    val semesters: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        grades.map { it.xnxqmc }.distinct().sortedDescending()
    }

    /** 学年候选：按学年前4位聚合（只含 1/2 学期），小学期(-3)单独一组。 */
    val schoolYears: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        val ys = grades.mapNotNull { g ->
            val m = SEMESTER_NAME_REGEX.find(g.xnxqmc)
            if (m == null) null else m.groupValues[1] + "-" + m.groupValues[2]
        }.distinct()
        // 保留有 1/2 的学年，3 归到"小学期"
        buildList {
            ys.filter { it.endsWith("-1") || it.endsWith("-2") }
                .map { it.substringBeforeLast("-") }
                .distinct()
                .sortedDescending()
                .forEach { add(it) }
            if (ys.any { it.endsWith("-3") }) add("小学期")
        }
    }

    /** 当前学年筛选下的学期候选（用于学期下拉；"全部学年"时列全部学期）。 */
    val semestersOfSchoolYear: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        when {
            schoolYearFilter.isBlank() -> semesters
            // 小学期组只列小学期：此前返回全部学期，用户能选中普通学期，
            // 选中后 matchesFilter 返回空 → 卡片显示"—"且没有任何解释。
            schoolYearFilter == "小学期" -> semesters.filter { it.endsWith("-3") }
            else -> semesters.filter { it.startsWith(schoolYearFilter) && !it.endsWith("-3") }
        }
    }

    /** 当前筛选是否命中某条成绩（学年 + 学期 双重口径）。 */
    private fun matchesFilter(g: GradeEntity): Boolean {
        // 学年维度
        val yearOk = when {
            schoolYearFilter.isBlank() -> true
            schoolYearFilter == "小学期" -> g.xnxqmc.endsWith("-3")
            else -> g.xnxqmc.startsWith(schoolYearFilter) && !g.xnxqmc.endsWith("-3")
        }
        if (!yearOk) return false
        // 学期维度（在学年基础上精确定到具体学期）
        if (semesterFilter.isNotBlank() && g.xnxqmc != semesterFilter) return false
        return true
    }

    /** 当前筛选下、已按 kcdm 收敛的成绩行（加权与勾选列表共用，两者必须口径一致）。 */
    private val filteredBestRows: List<GradeEntity> by lazy(LazyThreadSafetyMode.NONE) {
        GradeRows.bestPerCourse(grades.filter { matchesFilter(it) })
    }

    /** 加权成绩计算结果（当前筛选+勾选状态下）。 */
    val weightedResult: WeightedScoreCalculator.WeightedResult? by lazy(LazyThreadSafetyMode.NONE) {
        val triples = filteredBestRows.map {
            WeightedScoreCalculator.GradeTriple(
                kcdm = it.kcdm,
                xf = it.xf,
                score = it.numericScore,
                kcxz = it.kcxz,
            )
        }
        WeightedScoreCalculator.calculate(triples, excludedKcdm)
    }

    /** 当前筛选下参与加权计算的课程（UI 勾选列表用）。 */
    val weightEligible: List<Pair<GradeEntity, Boolean>> by lazy(LazyThreadSafetyMode.NONE) {
        filteredBestRows
            .filter { it.kcxz == "必修" && it.numericScore != null && it.xf > 0.0 }
            .map { it to (it.kcdm !in excludedKcdm) }
    }

    private companion object {
        /** 学期名形如 2025-2026-2；用于聚合学年。只编译一次，不再每次调用重新编译。 */
        val SEMESTER_NAME_REGEX = Regex("^(\\d{4}-\\d{4})-(\\d)$")
    }
}

class GradesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)
    private val error = MutableStateFlow<String?>(null)
    private val scoreMode = MutableStateFlow(ScoreMode.WEIGHTED)
    private val semesterFilter = MutableStateFlow("")
    private val schoolYearFilter = MutableStateFlow("")
    private val excludedKcdm = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 当前分段：从 DataStore 读取（"记住上次选择"），默认无课教室。
     * 用 ordinal 存整数，枚举顺序变化时旧值会落到相邻分段——因为这三个分段
     * 顺序是产品决定且短期不会变，用字符串名反而更脆（改类名就丢偏好）。
     */
    private val section: Flow<GradesSection> = repo.settings.gradesTabIndex.map { index ->
        GradesSection.entries.getOrElse(index) { GradesSection.FREE_ROOM }
    }

    /**
     * GPA 概览：跟随落盘的缓存 JSON。
     *
     * 抓取已经搬到「一键同步」页（由那边写盘），这里若还用"进页面读一次"的内存态，
     * 同步完成后 GPA 会一直显示旧值直到进程重启。
     */
    private val gpaFromCache: Flow<GpaInfo?> = repo.settings.gpaCache
        .map { GradesParser.parseGpa(it) }
        .distinctUntilChanged()

    /** 会被 combine 合并的本地 UI 偏好（gpa 必须在流内，否则刷新后有 GPA 不刷新的竞态）。 */
    private data class UiPrefs(
        val gpa: GpaInfo?,
        val error: String?,
        val mode: ScoreMode,
        val filter: String,
        val schoolYear: String,
        val excluded: Set<String>,
    )

    private data class FetchInfo(
        val fetchedAt: Long,
        val section: GradesSection,
        val hideScores: Boolean,
    )

    /**
     * 学业进度 JSON → 解析结果。
     *
     * 必须单独抽出来并去重：`xflbyq` 有 ~60KB，解析写在 combine 变换里时，
     * **任意一个上游发射都会重跑**（DataStore 写任何键这两个流都会重发射、
     * 点一次勾选框也会），而实际内容极少变化。
     */
    private val creditParsed: Flow<Pair<List<CreditCategory>, GraduationProgress?>> =
        combine(repo.settings.xflbyqJson, repo.settings.bxkqkJson) { a, b -> a to b }
            .distinctUntilChanged()
            .map { (xflbyq, bxkqk) ->
                CreditProgressParser.parseCategories(xflbyq) to CreditProgressParser.parseProgress(bxkqk)
            }

    val uiState: StateFlow<GradesUiState> = combine(
        repo.grades,
        repo.exams,
        creditParsed,
        combine(
            repo.settings.gradesFetchedAt, section, ScorePrivacy.hidden,
        ) { a, b, c -> FetchInfo(a, b, c) },
        combine(
            gpaFromCache, error, scoreMode, semesterFilter,
            combine(schoolYearFilter, excludedKcdm) { y, x -> y to x },
        ) { g, e, m, f, (y, x) -> UiPrefs(g, e, m, f, y, x) },
    ) { grades, exams, credit, info, prefs ->
        GradesUiState(
            section = info.section,
            grades = grades,
            exams = exams,
            gpa = prefs.gpa,
            fetchedAt = info.fetchedAt,
            error = prefs.error,
            scoreMode = prefs.mode,
            semesterFilter = prefs.filter,
            schoolYearFilter = prefs.schoolYear,
            excludedKcdm = prefs.excluded,
            creditCategories = credit.first,
            gradProgress = credit.second,
            hideScores = info.hideScores,
        )
    }
        // 解析层出任何意外都不能把整个 uiState 流打挂：stateIn 上游异常会走
        // 未捕获异常处理 → 崩进程，而落盘的坏 JSON 会让"每次进教务页都崩"、
        // 连自救入口（清除成绩缓存）都进不去。兜底为"无学业进度"，
        // 成绩/考试等其余部分照常显示。
        .catch { e ->
            if (e is CancellationException) throw e
            emit(GradesUiState(section = GradesSection.FREE_ROOM))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            // 初值必须与偏好的默认段一致（无课教室）：否则冷启动进教务的最前面几帧
            // 会按"成绩段 + 无数据"渲染一屏"还没有成绩数据"，然后才跳回无课教室
            GradesUiState(section = GradesSection.FREE_ROOM),
        )

    init {
        // todo 表任何变更（新增/编辑/删除/打卡）→ 全量重排日程提醒。
        // 与上课提醒同理按值去重：reschedule 内部会把已排 requestCode 写回 DataStore(TODO_REMINDER_CODES)，
        // 而本收集器源自同一 dataStore.data，不去重会形成「重排→写 codes→重发→重排」自激循环。
        viewModelScope.launch {
            repo.todos.distinctUntilChangedBy { it }
                .collect {
                    // 重排失败只允许"本轮不重排"：异常逃出 viewModelScope 会直接崩进程，
                    // 而这里由 Room 流驱动，坏数据会变成"每次改日程都崩"。
                    runCatching { TodoReminderScheduler.reschedule(getApplication()) }
                        .onFailure { e -> if (e is CancellationException) throw e }
                }
        }
    }

    fun dismissError() {
        error.value = null
    }

    /** 切换分段并记住（跨重启保留）。 */
    fun setSection(section: GradesSection) {
        viewModelScope.launch { repo.settings.setGradesTabIndex(section.ordinal) }
    }

    /** 成绩隐私开关：点击小眼睛切换显示/隐藏（会话级，退到后台自动复位隐藏）。 */
    fun toggleHideScores() {
        ScorePrivacy.toggle()
    }

    fun setScoreMode(mode: ScoreMode) {
        scoreMode.value = mode
    }

    fun setSemesterFilter(semester: String) {
        semesterFilter.value = semester
        schoolYearFilter.value = ""
    }

    fun setSchoolYearFilter(schoolYear: String) {
        schoolYearFilter.value = schoolYear
        semesterFilter.value = ""
    }

    /** 切换课程是否纳入加权计算。 */
    fun toggleExcluded(kcdm: String) {
        excludedKcdm.value = if (kcdm in excludedKcdm.value) excludedKcdm.value - kcdm
        else excludedKcdm.value + kcdm
    }
}
