package com.example.beikeschedule.data.repo

import android.content.Context
import com.example.beikeschedule.data.local.AppDatabase
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.local.GradeEntity
import com.example.beikeschedule.data.local.SectionTimeEntity
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.ui.theme.CourseColors
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** UI 唯一数据入口。 */
class ScheduleRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val courseDao = db.courseDao()
    private val sectionTimeDao = db.sectionTimeDao()
    private val gradeDao = db.gradeDao()
    val settings = SettingsStore(context)

    val courses: Flow<List<CourseEntity>> = courseDao.observeAll()
    val sectionTimes: Flow<List<SectionTimeEntity>> = sectionTimeDao.observeAll()
    val grades: Flow<List<GradeEntity>> = gradeDao.observeAll()

    /** 覆盖式写入成绩（全量刷新语义）。 */
    suspend fun replaceGrades(grades: List<GradeEntity>) {
        gradeDao.clear()
        gradeDao.insertAll(grades)
    }

    /** 覆盖式写入教务导入结果：先删旧导入数据，再插入新数据与节次时间。 */
    suspend fun replaceImportedData(
        courses: List<CourseEntity>,
        sectionTimes: List<SectionTimeEntity>,
    ) {
        courseDao.deleteBySource(CourseEntity.SOURCE_IMPORT)
        courseDao.insertAll(assignImportColors(courses))
        sectionTimeDao.clear()
        sectionTimeDao.insertAll(sectionTimes)
    }

    /**
     * 教务课程颜色去重：
     * - 同名课程（多时段）共享同一颜色；
     * - 有原始 XB 色值（且在色板索引内）的课程优先保留该色；
     * - 同一色值被多门不同课程占用时，后者顺延到下一个未占用的色板下标；
     * - 无固定时间课程（原 99999/无 KEY）不再用 name 哈希撞色，统一走分配。
     */
    suspend fun addManualCourse(course: CourseEntity) =
        courseDao.insert(course.copy(source = CourseEntity.SOURCE_MANUAL, taskId = ""))

    /** 原样插入课程行（保留 source，用于编辑展开后的多行写回）。 */
    suspend fun insertCourses(courses: List<CourseEntity>) = courseDao.insertAll(courses)

    /** 更新单行课程（手动课程编辑走保存替换时较少用；编辑展开用 insertCourses+deleteCourse）。 */
    suspend fun updateCourse(course: CourseEntity) = courseDao.update(course)

    /** 删除一门课的指定 id（手动课程删除；编辑替换旧行时也用它）。 */
    suspend fun deleteCourse(id: Long) = courseDao.deleteById(id)

    /** 隐藏/恢复教务导入课程（隐藏 = 不显示但保留；手动/示例删除用 deleteCourse）。 */
    suspend fun setCourseHidden(id: Long, hidden: Boolean) = courseDao.setHidden(id, hidden)

    /** 按源 + 课程名取全部行（含隐藏），用于多时段课程的整体编辑。 */
    fun observeCourseByName(sources: List<Int>, name: String): Flow<List<CourseEntity>> =
        courseDao.observeByNames(sources, name)

    /** 载入示例课表（assets 内置的真实教务样本），source=SOURCE_SAMPLE 便于一键清除。 */
    suspend fun loadSampleData(courses: List<CourseEntity>, sectionTimes: List<SectionTimeEntity>) {
        courseDao.deleteBySource(CourseEntity.SOURCE_SAMPLE)
        courseDao.insertAll(courses.map { it.copy(source = CourseEntity.SOURCE_SAMPLE, id = 0) })
        if (sectionTimeDao.getAll().isEmpty()) {
            sectionTimeDao.insertAll(sectionTimes)
        }
    }

    suspend fun clearSampleData() = courseDao.deleteBySource(CourseEntity.SOURCE_SAMPLE)

    companion object {
        /**
         * 教务课程颜色去重（纯函数，导入插入前调用）：
         * - 同名课程（多时段）共享同一颜色；
         * - 有原始 XB 色值（且在色板索引内）的课程优先保留该色；
         * - 同一色值被多门不同课程占用时，后者顺延到下一个未占用的色板下标；
         * - 无固定时间课程（原 99999/无 KEY）不再用 name 哈希撞色，统一走分配。
         */
        internal fun assignImportColors(courses: List<CourseEntity>): List<CourseEntity> {
            val paletteSize = 10 // 与 CourseColors.basePalette 尺寸一致
            val usedColors = mutableMapOf<Int, String>() // colorIndex -> 首次占用的课程名
            val nameColor = mutableMapOf<String, Int>()   // 课程名 -> 分配到的色
            val result = mutableListOf<CourseEntity>()

            fun pick(original: Int, courseName: String): Int {
                val norm = CourseColors.importedOf(original)
                // 同名已分配，复用
                nameColor[courseName]?.let { return it }
                // 优先尝试教务原始色（未被他课占用），否则顺延到未占用色
                val candidates = sequenceOf(norm) + (0 until paletteSize)
                for (c in candidates) {
                    val holder = usedColors[c]
                    if (holder == null || holder == courseName) {
                        usedColors[c] = courseName
                        nameColor[courseName] = c
                        return c
                    }
                }
                // 全部占满（理论上不可能，paletteSize 大于课程数），取模兜底
                val fallback = kotlin.math.abs(courseName.hashCode()) % paletteSize
                usedColors[fallback] = courseName
                nameColor[courseName] = fallback
                return fallback
            }

            courses.forEach { course ->
                val color = if (course.colorIndex == CourseEntity.COLOR_UNSCHEDULED) {
                    pick(kotlin.math.abs(course.name.hashCode()), course.name)
                } else {
                    pick(course.colorIndex, course.name)
                }
                result += course.copy(colorIndex = color)
            }
            return result
        }

        /**
         * 由第 1 周周一日期推算今天处于第几周；不在学期范围内返回 null。
         * firstMonday 格式 yyyy-MM-dd。
         */
        fun currentWeek(firstMonday: String, totalWeeks: Int, today: LocalDate = LocalDate.now()): Int? {
            if (firstMonday.isBlank()) return null
            val start = runCatching { LocalDate.parse(firstMonday) }.getOrNull() ?: return null
            val monday = if (start.dayOfWeek == DayOfWeek.MONDAY) {
                start
            } else {
                start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            }
            val week = (ChronoUnit.DAYS.between(monday, today) / 7 + 1).toInt()
            return if (week in 1..totalWeeks) week else null
        }

        /**
         * 今天在学期中的位置。
         * @param week 当前教学周；假期中为下一教学周；学期结束后为 null
         * @param isHoliday 今天是否处于被跳过的假期周（如国庆）
         * @param nextWeekMonday 假期时下一教学周的周一日期（用于提示）
         */
        data class WeekLocation(
            val week: Int?,
            val isHoliday: Boolean,
            val nextWeekMonday: String?,
        )

        /**
         * 用官方教学周日历定位今天：周→周一映射精确反映长假跳周（如国庆周不占序号）。
         * weekMondays 下标+1 = 教学周。未开学视为第 1 周；学期结束返回 week=null。
         */
        fun locateWeek(weekMondays: List<String>, today: LocalDate = LocalDate.now()): WeekLocation {
            val mondays = weekMondays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (mondays.isEmpty()) return WeekLocation(null, false, null)
            if (today.isBefore(mondays.first())) return WeekLocation(1, false, null)
            mondays.forEachIndexed { i, monday ->
                val sunday = monday.plusDays(6)
                if (!today.isBefore(monday) && !today.isAfter(sunday)) {
                    return WeekLocation(i + 1, false, null)
                }
                // 本周日与下周一之间的空隙 = 被跳过的假期周
                if (i + 1 < mondays.size && today.isAfter(sunday) && today.isBefore(mondays[i + 1])) {
                    return WeekLocation(i + 2, true, mondays[i + 1].toString())
                }
            }
            return WeekLocation(null, false, null)
        }

        /**
         * 严格判定日期属于第几教学周：开学前、假期跳周、学期结束后都返回 null。
         * 用于上课提醒排期（显示场景的"未开学视为第1周"语义在这里不适用）。
         */
        fun teachingWeekOf(weekMondays: List<String>, date: LocalDate): Int? {
            val mondays = weekMondays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (mondays.isEmpty() || date.isBefore(mondays.first())) return null
            mondays.forEachIndexed { i, monday ->
                if (!date.isBefore(monday) && !date.isAfter(monday.plusDays(6))) return i + 1
            }
            return null
        }
    }
}
