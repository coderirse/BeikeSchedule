package com.example.beikeschedule.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.local.SectionTimeEntity
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.data.repo.ScheduleRepository
import com.example.beikeschedule.import.parser.JwParser
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
        ScheduleUiState(
            courses = courses,
            sectionTimes = sections,
            semester = semester,
            selectedWeek = week.coerceIn(1, semester.totalWeeks),
            currentWeek = ScheduleRepository.currentWeek(semester.firstMonday, semester.totalWeeks),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

    init {
        // 初次进入默认选中当前周
        viewModelScope.launch {
            repo.settings.semester.collect { semester ->
                val cw = ScheduleRepository.currentWeek(semester.firstMonday, semester.totalWeeks)
                if (cw != null && selectedWeek.value == 1) selectedWeek.value = cw
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
