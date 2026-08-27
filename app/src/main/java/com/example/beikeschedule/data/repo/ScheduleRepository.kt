package com.example.beikeschedule.data.repo

import android.content.Context
import com.example.beikeschedule.data.local.AppDatabase
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.local.SectionTimeEntity
import com.example.beikeschedule.data.pref.SettingsStore
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
    val settings = SettingsStore(context)

    val courses: Flow<List<CourseEntity>> = courseDao.observeAll()
    val sectionTimes: Flow<List<SectionTimeEntity>> = sectionTimeDao.observeAll()

    /** 覆盖式写入教务导入结果：先删旧导入数据，再插入新数据与节次时间。 */
    suspend fun replaceImportedData(
        courses: List<CourseEntity>,
        sectionTimes: List<SectionTimeEntity>,
    ) {
        courseDao.deleteBySource(CourseEntity.SOURCE_IMPORT)
        courseDao.insertAll(courses)
        sectionTimeDao.clear()
        sectionTimeDao.insertAll(sectionTimes)
    }

    suspend fun addManualCourse(course: CourseEntity) =
        courseDao.insert(course.copy(source = CourseEntity.SOURCE_MANUAL, taskId = ""))

    suspend fun updateCourse(course: CourseEntity) = courseDao.update(course)

    suspend fun deleteCourse(id: Long) = courseDao.deleteById(id)

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
    }
}
