package com.example.beikeschedule.import

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.local.SectionTimeEntity
import com.example.beikeschedule.data.repo.ScheduleRepository
import com.example.beikeschedule.import.parser.JwParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 教务导入状态机。 */
sealed interface ImportUiState {
    /** 用户在 WebView 中浏览/登录。 */
    data object Browsing : ImportUiState

    /** 脚本已注入，正在抓取接口。 */
    data object Fetching : ImportUiState

    /** 抓取成功，等待用户确认写入。 */
    data class Preview(
        val semesterName: String,
        val xn: String,
        val xq: String,
        val firstMonday: String,
        val courses: List<CourseEntity>,
        val sectionTimes: List<SectionTimeEntity>,
    ) : ImportUiState {
        val scheduledCount get() = courses.count { !it.isUnscheduled }
        val unscheduledCount get() = courses.count { it.isUnscheduled }
    }

    data class Error(val message: String) : ImportUiState
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Browsing)
    val state: StateFlow<ImportUiState> = _state

    fun onFetchStart() {
        _state.value = ImportUiState.Fetching
    }

    /** JsBridge 回调：脚本抓取完成（可能在 WebView 线程，切到主线程状态更新即可）。 */
    fun onFetchResult(semester: String, published: String, courses: String, sections: String, weekDates: String) {
        try {
            if (published.trim() == "0") {
                _state.value = ImportUiState.Error("本学期课表尚未发布，请稍后再试")
                return
            }
            val (xn, xq, name) = JwParser.parseCurrentSemester(semester)
            val courseList = JwParser.parseCourses(courses)
            if (courseList.isEmpty()) {
                _state.value = ImportUiState.Error("未解析到课程，请确认已进入教务系统课表页")
                return
            }
            _state.value = ImportUiState.Preview(
                semesterName = name.ifBlank { "$xn-$xq" },
                xn = xn,
                xq = xq,
                firstMonday = JwParser.parseFirstMonday(weekDates).orEmpty(),
                courses = courseList,
                sectionTimes = JwParser.parseSectionTimes(sections),
            )
        } catch (e: Exception) {
            _state.value = ImportUiState.Error("解析失败：${e.message}")
        }
    }

    fun onFetchError(message: String) {
        _state.value = ImportUiState.Error("抓取失败：$message")
    }

    fun backToBrowsing() {
        _state.value = ImportUiState.Browsing
    }

    /** 确认导入：覆盖式写入课程与节次时间，写入学期配置，清除示例数据。 */
    fun confirmImport(onDone: () -> Unit) {
        val preview = _state.value as? ImportUiState.Preview ?: return
        viewModelScope.launch {
            repo.replaceImportedData(preview.courses, preview.sectionTimes)
            repo.clearSampleData()
            repo.settings.saveSemester(
                com.example.beikeschedule.data.pref.SettingsStore.SemesterConfig(
                    xn = preview.xn,
                    xq = preview.xq,
                    name = preview.semesterName,
                    firstMonday = preview.firstMonday,
                )
            )
            onDone()
        }
    }
}
