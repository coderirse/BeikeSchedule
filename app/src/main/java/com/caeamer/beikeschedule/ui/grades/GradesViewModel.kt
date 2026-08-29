package com.caeamer.beikeschedule.ui.grades

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.repo.CreditAggregator
import com.caeamer.beikeschedule.data.repo.GpaCalculator
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 成绩展示模式：加权（默认，只看必修数字成绩）/ GPA（教务官方值）。 */
enum class ScoreMode { WEIGHTED, GPA }

/** 教务 Tab 分段：成绩 / 考试。 */
enum class GradesSection { SCORES, EXAMS }

/** 学分进度行：类别要求（接口）+ 本地已完成（成绩汇总）。 */
data class CreditRow(val category: CreditCategory, val completed: Double)

data class GradesUiState(
    val showWebView: Boolean = false,
    val fetching: Boolean = false,
    val section: GradesSection = GradesSection.SCORES,
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
) {
    /** 本地 4.0 制 GPA：全部有数字成绩的课程，补考/重修覆盖正考（教务网 BL 是平均学分绩/20 口径，不可用）。 */
    val localGpa: GpaCalculator.GpaResult? get() = GpaCalculator.calculate(grades)
    /** 按学期分组（学期名倒序，学期内按原始顺序）。 */
    val grouped: List<Pair<String, List<GradeEntity>>>
        get() = grades.groupBy { it.xnxqmc }.toSortedMap(compareByDescending { it }).map { (k, v) -> k to v }

    /** 每学期挂科门数（学期名 → N）。 */
    val failedBySemester: Map<String, Int>
        get() = grades.filter { it.isFailed }.groupBy { it.xnxqmc }.mapValues { it.value.size }

    /** 本地按课程类别汇总的已通过学分（与教务网页口径一致）。 */
    val localCategorySums: Map<String, Double>
        get() = CreditAggregator.sumPassedByCategory(grades)

    /** 学分进度行：要求（接口）× 已完成（本地汇总）。 */
    val creditRows: List<CreditRow>
        get() {
            val sums = localCategorySums
            return creditCategories.map { CreditRow(it, CreditAggregator.completedCreditsFor(sums, it.kclbmc)) }
        }

    /** 考试按日期升序（无日期的排最后，按原文排序）。 */
    val examsSorted: List<ExamEntity>
        get() = exams.sortedWith(
            compareByDescending<ExamEntity> { it.hasDate }
                .thenBy { it.ksrq }
                .thenBy { it.kssj }
                .thenBy { it.kssjms },
        )

    /** 去重后的学期列表（用于筛选）。 */
    val semesters: List<String> get() = grades.map { it.xnxqmc }.distinct().sortedDescending()

    /** 学年候选：按学年前4位聚合（只含 1/2 学期），小学期(-3)单独一组。 */
    val schoolYears: List<String> get() {
        val ys = grades.mapNotNull { g ->
            val m = Regex("^(\\d{4}-\\d{4})-(\\d)$").find(g.xnxqmc)
            if (m == null) null else m.groupValues[1] + "-" + m.groupValues[2]
        }.distinct()
        // 保留有 1/2 的学年，3 归到"小学期"
        return buildList {
            ys.filter { it.endsWith("-1") || it.endsWith("-2") }
                .map { it.substringBeforeLast("-") }
                .distinct()
                .sortedDescending()
                .forEach { add(it) }
            if (ys.any { it.endsWith("-3") }) add("小学期")
        }
    }

    /** 当前学年筛选下的学期候选（用于学期下拉；"全部学年"时列全部学期）。 */
    val semestersOfSchoolYear: List<String>
        get() {
            if (schoolYearFilter.isBlank() || schoolYearFilter == "小学期") return semesters
            return semesters.filter { it.startsWith(schoolYearFilter) && !it.endsWith("-3") }
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

    /** 加权成绩计算结果（当前筛选+勾选状态下）。 */
    val weightedResult: WeightedScoreCalculator.WeightedResult?
        get() {
            val filtered = grades.filter { matchesFilter(it) }
            val triples = filtered.map {
                WeightedScoreCalculator.GradeTriple(xf = it.xf, score = it.numericScore, kcxz = it.kcxz)
            }
            val excludedIdx = filtered.mapIndexedNotNull { idx, g ->
                if (g.kcdm in excludedKcdm) idx else null
            }.toSet()
            return WeightedScoreCalculator.calculate(triples, excludedIdx)
        }

    /** 当前筛选下参与加权计算的课程（UI 勾选列表用）。 */
    val weightEligible: List<Pair<GradeEntity, Boolean>>
        get() {
            val filtered = grades.filter { matchesFilter(it) }
            return filtered
                .filter { it.kcxz == "必修" && it.numericScore != null }
                .map { it to (it.kcdm !in excludedKcdm) }
        }
}

class GradesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)
    private val showWebView = MutableStateFlow(false)
    private val fetching = MutableStateFlow(false)
    private val gpaFromCache = MutableStateFlow<GpaInfo?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val section = MutableStateFlow(GradesSection.SCORES)
    private val scoreMode = MutableStateFlow(ScoreMode.WEIGHTED)
    private val semesterFilter = MutableStateFlow("")
    private val schoolYearFilter = MutableStateFlow("")
    private val excludedKcdm = MutableStateFlow<Set<String>>(emptySet())

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
        val showWebView: Boolean,
        val fetching: Boolean,
        val section: GradesSection,
    )

    val uiState: StateFlow<GradesUiState> = combine(
        repo.grades,
        repo.exams,
        combine(repo.settings.xflbyqJson, repo.settings.bxkqkJson) { a, b -> a to b },
        combine(repo.settings.gradesFetchedAt, showWebView, fetching, section) { a, b, c, d ->
            FetchInfo(a, b, c, d)
        },
        combine(
            gpaFromCache, error, scoreMode, semesterFilter,
            combine(schoolYearFilter, excludedKcdm) { y, x -> y to x },
        ) { g, e, m, f, (y, x) -> UiPrefs(g, e, m, f, y, x) },
    ) { grades, exams, creditJson, info, prefs ->
        GradesUiState(
            showWebView = info.showWebView,
            fetching = info.fetching,
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
            creditCategories = CreditProgressParser.parseCategories(creditJson.first),
            gradProgress = CreditProgressParser.parseProgress(creditJson.second),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GradesUiState())

    init {
        viewModelScope.launch {
            gpaFromCache.value = parseCachedGpa()
            // 从未抓取过 → 进入即走抓取流程
            if (repo.grades.first().isEmpty() && repo.settings.gradesFetchedAt.first() == 0L) {
                showWebView.value = true
            }
        }
    }

    private suspend fun parseCachedGpa(): GpaInfo? =
        GradesParser.parseGpa(repo.settings.gpaCache.first())

    fun onFetchStart() {
        fetching.value = true
        error.value = null
    }

    /** GradesBridge 回调：成绩+GPA+学籍+当前学期+考试+学业进度，一次会话全量。 */
    fun onFetchResult(
        gpaJson: String,
        gradesJson: String,
        userJson: String,
        xsxxJson: String,
        semJson: String,
        examsJson: String,
        xflbyqJson: String,
        bxkqkJson: String,
    ) {
        viewModelScope.launch {
            try {
                val grades = GradesParser.parseGrades(gradesJson)
                if (grades.isEmpty()) {
                    error.value = "未解析到成绩，请确认已在教务系统完成评教/成绩发布后重试"
                } else {
                    repo.replaceGrades(grades)
                    repo.settings.saveGradesMeta(gpaJson, System.currentTimeMillis())
                    gpaFromCache.value = GradesParser.parseGpa(gpaJson)
                    // 学籍快照顺手存（"我的"页离线展示）
                    GradesParser.parseStudentProfile(userJson, xsxxJson)?.let {
                        repo.settings.saveStudentProfile(it)
                    }
                    // 考试安排（仅当前学期）
                    val (semXn, semXq, _) = JwParser.parseCurrentSemester(semJson)
                    val exams = ExamsParser.parseExams(examsJson, semXn + semXq)
                    repo.replaceExams(exams)
                    // 学业进度缓存
                    if (xflbyqJson.isNotBlank() || bxkqkJson.isNotBlank()) {
                        repo.settings.saveCreditMeta(xflbyqJson, bxkqkJson)
                    }
                    // 考试有数据 → 重排考前提醒
                    if (exams.isNotEmpty()) {
                        ExamReminderScheduler.reschedule(getApplication())
                    }
                    error.value = null
                }
            } catch (e: Exception) {
                error.value = "解析失败：${e.message}"
            } finally {
                fetching.value = false
                showWebView.value = false
            }
        }
    }

    fun onFetchError(message: String) {
        fetching.value = false
        showWebView.value = false
        error.value = "抓取失败：$message"
    }

    fun startRefresh() {
        error.value = null
        showWebView.value = true
    }

    fun dismissError() {
        error.value = null
    }

    fun setSection(section: GradesSection) {
        this.section.value = section
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
