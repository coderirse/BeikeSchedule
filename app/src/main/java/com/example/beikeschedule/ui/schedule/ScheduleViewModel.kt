package com.example.beikeschedule.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.local.SectionTimeEntity
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.data.repo.ScheduleRepository
import com.example.beikeschedule.import.parser.JwParser
import com.example.beikeschedule.reminder.ClassReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class ScheduleUiState(
    val courses: List<CourseEntity> = emptyList(),
    val sectionTimes: List<SectionTimeEntity> = emptyList(),
    val semester: SettingsStore.SemesterConfig = SettingsStore.SemesterConfig(),
    val selectedWeek: Int = 1,
    val currentWeek: Int? = null,
    /** 今天是否处于被跳过的假期周（如国庆）；此时 currentWeek 指向假期后第一个教学周。 */
    val inHoliday: Boolean = false,
    /** 假期提示：假期后第一个教学周的周一日期。 */
    val nextWeekMonday: String? = null,
    val loaded: Boolean = false,
) {
    val scheduledCourses get() = courses.filter { !it.isUnscheduled }
    val unscheduledCourses get() = courses.filter { it.isUnscheduled }
    val hasSample get() = courses.any { it.source == CourseEntity.SOURCE_SAMPLE }
}

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository(app)

    private val selectedWeek = MutableStateFlow(1)

    val uiState: StateFlow<ScheduleUiState> = combine(
        repo.courses,
        repo.sectionTimes,
        repo.settings.semester,
        selectedWeek,
    ) { courses, sections, semester, week ->
        val location = locateWeek(semester)
        ScheduleUiState(
            courses = courses,
            sectionTimes = sections,
            semester = semester,
            selectedWeek = week.coerceIn(1, semester.totalWeeks),
            currentWeek = location.week,
            inHoliday = location.isHoliday,
            nextWeekMonday = location.nextWeekMonday,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

    val reminderEnabled: StateFlow<Boolean> = repo.settings.reminderEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val reminderMinutes: StateFlow<Int> = repo.settings.reminderMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    val themeMode: StateFlow<SettingsStore.ThemeMode> = repo.settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.ThemeMode.SYSTEM)

    fun setReminder(enabled: Boolean, minutes: Int) {
        viewModelScope.launch { repo.settings.setReminder(enabled, minutes) }
    }

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        viewModelScope.launch { repo.settings.setThemeMode(mode) }
    }

    /** 有官方教学周日历时用它定位（精确反映长假跳周），否则按开学日期推算。 */
    private fun locateWeek(semester: SettingsStore.SemesterConfig): ScheduleRepository.Companion.WeekLocation =
        if (semester.weekMondays.isNotEmpty()) {
            ScheduleRepository.locateWeek(semester.weekMondays)
        } else {
            ScheduleRepository.Companion.WeekLocation(
                week = ScheduleRepository.currentWeek(semester.firstMonday, semester.totalWeeks),
                isHoliday = false,
                nextWeekMonday = null,
            )
        }

    init {
        // 初次进入默认选中当前周（假期时选中假期后第一个教学周）
        viewModelScope.launch {
            repo.settings.semester.collect { semester ->
                val cw = locateWeek(semester).week
                if (cw != null && selectedWeek.value == 1) selectedWeek.value = cw
            }
        }
        // 课程/学期/提醒设置任一变化 → 全量重排上课提醒闹钟
        viewModelScope.launch {
            var first = true
            combine(
                repo.courses,
                repo.settings.semester,
                repo.settings.reminderEnabled,
                repo.settings.reminderMinutes,
            ) { _, _, _, _ -> Unit }.collect {
                // 跳过 stateIn 初始空数据触发的首次重排，DB 就绪后的第二次发射才是真数据
                if (first) {
                    first = false
                    repo.courses.first() // 等待真实数据就位
                }
                ClassReminderScheduler.reschedule(getApplication())
            }
        }
    }

    fun selectWeek(week: Int) {
        selectedWeek.value = week
    }

    fun saveCourse(course: CourseEntity) {
        viewModelScope.launch {
            if (course.id == 0L) repo.addManualCourse(course) else repo.updateCourse(course)
        }
    }

    fun deleteCourse(id: Long) {
        viewModelScope.launch { repo.deleteCourse(id) }
    }

    /** 从 assets 载入示例课表；若未设置开学日期，则把本周一设为第 1 周周一便于立即查看。 */
    fun loadSampleData() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val coursesJson = ctx.assets.open("sample/courses.json").bufferedReader().use { it.readText() }
            val sectionsJson = ctx.assets.open("sample/sections.json").bufferedReader().use { it.readText() }
            repo.loadSampleData(JwParser.parseCourses(coursesJson), JwParser.parseSectionTimes(sectionsJson))
            val semester = repo.settings.semester.first()
            if (semester.firstMonday.isBlank()) {
                val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                repo.settings.saveSemester(
                    semester.copy(
                        name = if (semester.name.isBlank()) "示例学期" else semester.name,
                        firstMonday = monday.toString(),
                    )
                )
            }
        }
    }

    fun clearSampleData() {
        viewModelScope.launch { repo.clearSampleData() }
    }

    fun saveSemester(config: SettingsStore.SemesterConfig) {
        viewModelScope.launch { repo.settings.saveSemester(config) }
    }
}
