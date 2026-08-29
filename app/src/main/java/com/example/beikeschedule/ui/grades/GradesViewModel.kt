package com.example.beikeschedule.ui.grades

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.beikeschedule.data.local.GradeEntity
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.data.repo.ScheduleRepository
import com.example.beikeschedule.import.parser.GradesParser
import com.example.beikeschedule.import.parser.GpaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GradesUiState(
    /** 是否显示 WebView 抓取流程（无缓存首次进入，或用户手动刷新）。 */
    val showWebView: Boolean = false,
    /** WebView 抓取进行中（已到主页，脚本已注入）。 */
    val fetching: Boolean = false,
    val grades: List<GradeEntity> = emptyList(),
    val gpa: GpaInfo? = null,
    val fetchedAt: Long = 0L,
    val error: String? = null,
) {
    /** 按学期分组（学期名倒序，学期内按原始顺序）。 */
    val grouped: List<Pair<String, List<GradeEntity>>>
        get() = grades.groupBy { it.xnxqmc }.toSortedMap(compareByDescending { it }).map { (k, v) -> k to v }

    val gpaCreditsSum: Double get() = grades.sumOf { it.xf }
}

class GradesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)
    private val showWebView = MutableStateFlow(false)
    private val fetching = MutableStateFlow(false)
    private val gpaFromCache = MutableStateFlow<GpaInfo?>(null)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GradesUiState> = combine(
        repo.grades,
        repo.settings.gradesFetchedAt,
        showWebView,
        fetching,
        error,
    ) { grades, fetchedAt, webView, fetchingNow, err ->
        GradesUiState(
            showWebView = webView,
            fetching = fetchingNow,
            grades = grades,
            gpa = gpaFromCache.value ?: parseCachedGpa(),
            fetchedAt = fetchedAt,
            error = err,
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

    /** GradesBridge 回调。 */
    fun onFetchResult(gpaJson: String, gradesJson: String) {
        viewModelScope.launch {
            try {
                val grades = GradesParser.parseGrades(gradesJson)
                if (grades.isEmpty()) {
                    error.value = "未解析到成绩，请确认已在教务系统完成评教/成绩发布后重试"
                } else {
                    repo.replaceGrades(grades)
                    repo.settings.saveGradesMeta(gpaJson, System.currentTimeMillis())
                    gpaFromCache.value = GradesParser.parseGpa(gpaJson)
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

    /** 手动刷新：重新进入 WebView 抓取流程。 */
    fun startRefresh() {
        error.value = null
        showWebView.value = true
    }

    fun dismissError() {
        error.value = null
    }
}
