package com.caeamer.beikeschedule.import

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.parser.JwParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
        /** 官方教学周日历（下标+1 = 教学周 → 周一日期）；为空则回退推算。 */
        val weekMondays: List<String>,
        /** 教务学期总教学周数（来自 queryzclist/校历）。 */
        val totalWeeks: Int,
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
    fun onFetchResult(
        semester: String,
        published: String,
        courses: String,
        sections: String,
        weekDates: String,
        calendar: String,
    ) {
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
            val weekCalendar = JwParser.parseWeekCalendar(calendar)
            _state.value = ImportUiState.Preview(
                semesterName = name.ifBlank { "$xn-$xq" },
                xn = xn,
                xq = xq,
                firstMonday = JwParser.parseFirstMonday(weekDates)
                    ?: weekCalendar.weekMondays.firstOrNull().orEmpty(),
                weekMondays = weekCalendar.weekMondays,
                totalWeeks = weekCalendar.totalWeeks.takeIf { it > 0 } ?: 20,
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

    /** 确认导入：覆盖式写入课程与节次时间，写入学期配置与教学周日历，清除示例数据。 */
    fun confirmImport(onDone: () -> Unit) {
        val preview = _state.value as? ImportUiState.Preview ?: return
        viewModelScope.launch {
            repo.replaceImportedData(preview.courses, preview.sectionTimes)
            repo.clearSampleData()
            val previous = repo.settings.semester.first()
            repo.settings.saveSemester(
                previous.copy(
                    xn = preview.xn,
                    xq = preview.xq,
                    name = preview.semesterName,
                    firstMonday = preview.firstMonday,
                    totalWeeks = preview.totalWeeks,
                    weekMondays = preview.weekMondays,
                )
            )
            onDone()
        }
    }
}
