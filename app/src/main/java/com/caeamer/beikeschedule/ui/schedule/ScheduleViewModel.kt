package com.caeamer.beikeschedule.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.import.parser.JwParser
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    /** 开学前（显示语义仍视为第 1 周，但状态文案应显示"未开学"）。 */
    val beforeStart: Boolean = false,
    /** 学期已结束（currentWeek=null）。 */
    val afterEnd: Boolean = false,
    val loaded: Boolean = false,
) {
    /**
     * 未隐藏的有固定时间课程。
     * 用 val 在构造时算一次，而不是 `get()`：`get()` 每次读取都新建一个 List，
     * 下游 `remember(state.scheduledCourses)` / `items(list)` 的键于是每次都变，
     * 白做整轮过滤与 diff（无固定时间弹层的抽搐就与这种不稳定列表有关）。
     */
    val scheduledCourses: List<CourseEntity> = courses.filter { !it.isUnscheduled && !it.hidden }
    /** 未隐藏的无固定时间课程。 */
    val unscheduledCourses: List<CourseEntity> = courses.filter { it.isUnscheduled && !it.hidden }
    /** 已隐藏的课程（教务导入课程可隐藏，供学期设置里恢复）。 */
    val hiddenCourses: List<CourseEntity> = courses.filter { it.hidden }
    val hasSample: Boolean = courses.any { it.source == CourseEntity.SOURCE_SAMPLE }
}

/**
 * 提醒排期诊断信息（设置页展示）：让"到底排上了没有 / 下次什么时候响"不用抓 logcat 就能看到。
 * 系统层面的通知开关、精确闹钟权限是同步查询，放在设置页组合时现算，保证每次打开都是最新值。
 */
data class ReminderScheduleInfo(
    val scheduledCount: Int = 0,
    /** 最近一次提醒的触发时刻（epoch 毫秒）；没有未来提醒时为 null。 */
    val nextTriggerAtMillis: Long? = null,
)

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
            beforeStart = location.beforeStart,
            afterEnd = location.afterEnd,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduleUiState())

    val reminderEnabled: StateFlow<Boolean> = repo.settings.reminderEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val reminderMinutes: StateFlow<Int> = repo.settings.reminderMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    val themeMode: StateFlow<SettingsStore.ThemeMode> = repo.settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.ThemeMode.SYSTEM)
    val hideWeekend: StateFlow<Boolean> = repo.settings.hideWeekend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 「隐藏本周不上的课」：与「我的」Tab 里的开关共用同一个 DataStore 键，两边即时同步。 */
    val hideInactiveCourses: StateFlow<Boolean> = repo.settings.hideInactiveCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 提醒排期状态：已排上的未来闹钟数量与最近一次触发时刻（设置页 diagnostics 用）。 */
    val reminderSchedule: StateFlow<ReminderScheduleInfo> = repo.settings.reminderScheduledAlarms
        .map { alarms ->
            val now = System.currentTimeMillis()
            val future = alarms.mapNotNull { it.triggerAtMillis }.filter { it > now }
            ReminderScheduleInfo(future.size, future.minOrNull())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReminderScheduleInfo())

    fun setHideWeekend(hidden: Boolean) {
        viewModelScope.launch { repo.settings.setHideWeekend(hidden) }
    }

    fun setHideInactiveCourses(hidden: Boolean) {
        viewModelScope.launch { repo.settings.setHideInactiveCourses(hidden) }
    }

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
            val week = ScheduleRepository.currentWeek(semester.firstMonday, semester.totalWeeks)
            val start = runCatching { java.time.LocalDate.parse(semester.firstMonday) }.getOrNull()
            val today = java.time.LocalDate.now()
            ScheduleRepository.Companion.WeekLocation(
                week = week,
                isHoliday = false,
                nextWeekMonday = null,
                beforeStart = start != null && today.isBefore(start),
                afterEnd = week == null && start != null &&
                    today.isAfter(start.plusWeeks(semester.totalWeeks.toLong())),
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
        // 课程/学期/提醒设置任一变化 → 全量重排上课提醒闹钟。
        // 必须按值去重：reschedule() 内部会把已排 requestCode 写回 DataStore(REMINDER_CODES)，
        // 而下面三个设置流都源自同一个 DataStore.data，写任何键都会让它们重新发射（map 不去重），
        // 不去重就会形成「重排→写 codes→重新发射→重排」的自激循环，闹钟被反复取消重设。
        viewModelScope.launch {
            combine(
                repo.courses,
                repo.settings.semester,
                repo.settings.reminderEnabled,
                repo.settings.reminderMinutes,
            ) { courses, semester, enabled, minutes ->
                ReminderKey(courses, semester, enabled, minutes)
            }.distinctUntilChanged().collect {
                ClassReminderScheduler.reschedule(getApplication())
            }
        }
    }

    /** 重排触发条件的值快照：用于过滤 DataStore 的无关键写入（见 init 注释）。 */
    private data class ReminderKey(
        val courses: List<CourseEntity>,
        val semester: SettingsStore.SemesterConfig,
        val enabled: Boolean,
        val minutes: Int,
    )

    fun selectWeek(week: Int) {
        selectedWeek.value = week
    }

    fun saveCourse(course: CourseEntity) {
        viewModelScope.launch {
            if (course.id == 0L) repo.addManualCourse(course) else repo.updateCourse(course)
        }
    }

    /**
     * 批量保存一门课：编辑场景先删除被替换的全部旧行，再插入展开后的全部时段行（单事务）。
     * 多时段课程编辑：传入该课程的所有行（同名同源），先删旧行再插入新行。
     */
    fun saveCourses(courses: List<CourseEntity>, replaceIds: List<Long>?) {
        viewModelScope.launch {
            repo.replaceCourses(replaceIds.orEmpty(), courses)
        }
    }

    /** 按名字+源加载一门课的全部行（多时段课程整体编辑用）。 */
    fun observeCourseByName(sources: List<Int>, name: String) =
        repo.observeCourseByName(sources, name)

    /** 隐藏/恢复教务导入课程。 */
    fun setCourseHidden(id: Long, hidden: Boolean) {
        viewModelScope.launch { repo.setCourseHidden(id, hidden) }
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
