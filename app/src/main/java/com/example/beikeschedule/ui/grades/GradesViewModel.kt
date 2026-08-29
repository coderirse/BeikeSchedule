package com.example.beikeschedule.ui.grades

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.beikeschedule.data.local.GradeEntity
import com.example.beikeschedule.data.repo.ScheduleRepository
import com.example.beikeschedule.data.repo.WeightedScoreCalculator
import com.example.beikeschedule.import.parser.GradesParser
import com.example.beikeschedule.import.parser.GpaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 成绩展示模式：加权（默认，只看必修数字成绩）/ GPA（教务官方值）。 */
enum class ScoreMode { WEIGHTED, GPA }

data class GradesUiState(
    val showWebView: Boolean = false,
    val fetching: Boolean = false,
    val grades: List<GradeEntity> = emptyList(),
    val gpa: GpaInfo? = null,
    val fetchedAt: Long = 0L,
    val error: String? = null,
    val scoreMode: ScoreMode = ScoreMode.WEIGHTED,
    /** 学期筛选：空=全部学期。 */
    val semesterFilter: String = "",
    /** 用户手动排除出加权计算的课程代码集合。 */
    val excludedKcdm: Set<String> = emptySet(),
) {
    /** 按学期分组（学期名倒序，学期内按原始顺序）。 */
    val grouped: List<Pair<String, List<GradeEntity>>>
        get() = grades.groupBy { it.xnxqmc }.toSortedMap(compareByDescending { it }).map { (k, v) -> k to v }

    /** 去重后的学期列表（用于筛选 chips）。 */
    val semesters: List<String> get() = grades.map { it.xnxqmc }.distinct().sortedDescending()

    /** 加权成绩计算结果（当前筛选+勾选状态下）。 */
    val weightedResult: WeightedScoreCalculator.WeightedResult?
        get() {
            val filtered = if (semesterFilter.isBlank()) grades
            else grades.filter { it.xnxqmc == semesterFilter }
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
            val filtered = if (semesterFilter.isBlank()) grades
            else grades.filter { it.xnxqmc == semesterFilter }
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
    private val scoreMode = MutableStateFlow(ScoreMode.WEIGHTED)
    private val semesterFilter = MutableStateFlow("")
    private val excludedKcdm = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<GradesUiState> = combine(
        repo.grades,
        repo.settings.gradesFetchedAt,
        showWebView,
        fetching,
        combine(error, scoreMode, semesterFilter, excludedKcdm) { e, m, f, x -> Quad(e, m, f, x) },
    ) { grades, fetchedAt, webView, fetchingNow, quad ->
        GradesUiState(
            showWebView = webView,
            fetching = fetchingNow,
            grades = grades,
            gpa = gpaFromCache.value,
            fetchedAt = fetchedAt,
            error = quad.error,
            scoreMode = quad.mode,
            semesterFilter = quad.filter,
            excludedKcdm = quad.excluded,
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

    private data class Quad(
        val error: String?,
        val mode: ScoreMode,
        val filter: String,
        val excluded: Set<String>,
    )

    private suspend fun parseCachedGpa(): GpaInfo? =
        GradesParser.parseGpa(repo.settings.gpaCache.first())

    fun onFetchStart() {
        fetching.value = true
        error.value = null
    }

    /** GradesBridge 回调（含学籍快照）。 */
    fun onFetchResult(gpaJson: String, gradesJson: String, userJson: String) {
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
                    GradesParser.parseStudentProfile(userJson)?.let {
                        repo.settings.saveStudentProfile(it)
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

    fun setScoreMode(mode: ScoreMode) {
        scoreMode.value = mode
    }

    fun setSemesterFilter(semester: String) {
        semesterFilter.value = semester
    }

    /** 切换课程是否纳入加权计算。 */
    fun toggleExcluded(kcdm: String) {
        excludedKcdm.value = if (kcdm in excludedKcdm.value) excludedKcdm.value - kcdm
        else excludedKcdm.value + kcdm
    }
}
