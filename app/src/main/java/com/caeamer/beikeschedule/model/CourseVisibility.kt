package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity
import java.time.LocalDate

/** 单次隐藏以实际日期为准，补课日与其生效星期的原日期互不影响。 */
object CourseVisibility {
    fun dates(course: CourseEntity): Set<LocalDate> = course.hiddenDates.split(',')
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()

    fun withDate(course: CourseEntity, date: LocalDate, hidden: Boolean): String =
        (if (hidden) dates(course) + date else dates(course) - date).sorted().joinToString(",")

    fun visibleOn(course: CourseEntity, date: LocalDate): Boolean =
        !course.hidden && date !in dates(course)

    /** 必须先过滤原始行再合并，否则基准行的例外会覆盖其他周次行的状态。 */
    fun onDate(courses: List<CourseEntity>, date: LocalDate): List<CourseEntity> =
        CourseMerger.mergeSameSlot(courses.filter { visibleOn(it, date) })

    /** 导入会重新分配 id；按来源、任务、时段与地点匹配，允许教务重新拆分周次行。 */
    fun preserveDates(previous: List<CourseEntity>, incoming: List<CourseEntity>): List<CourseEntity> {
        val previousLocations = cardLocations(previous)
        val incomingLocations = cardLocations(incoming)
        return incoming.map { next ->
            val matching = previous.filter { old ->
                old.source == next.source && old.taskId == next.taskId && old.name == next.name &&
                    old.dayOfWeek == next.dayOfWeek && old.startSection == next.startSection &&
                    old.endSection == next.endSection && previousLocations[old] == incomingLocations[next]
            }
            next.copy(hiddenDates = (dates(next) + matching.flatMap { dates(it) }).sorted().joinToString(","))
        }
    }

    // “-”地点行归属其卡片的实际地点，不能作为通配符把隐藏日期传给另一间教室的卡片。
    private fun cardLocations(courses: List<CourseEntity>): Map<CourseEntity, String> =
        CourseMerger.cardGroups(courses).flatMap { rows ->
            val location = rows.firstOrNull { CourseMerger.plausibleLocation(it.location) }?.location
                ?: rows.first().location
            rows.map { it to CourseMerger.stripCampusPrefix(location) }
        }.toMap()

    data class HiddenEntry(val rows: List<CourseEntity>, val date: LocalDate? = null) {
        val course: CourseEntity get() = rows.first()
        val ids: List<Long> get() = rows.map { it.id }
    }

    fun hiddenEntries(courses: List<CourseEntity>): List<HiddenEntry> {
        val whole = courses.filter { it.hidden }.groupBy { it.name to it.source }
            .values.map { HiddenEntry(it) }
        val occurrences = CourseMerger.cardGroups(courses.filter { !it.hidden }).flatMap { rows ->
            rows.flatMap { dates(it) }.distinct().sorted().map { date ->
                HiddenEntry(rows.filter { date in dates(it) }, date)
            }
        }
        return (whole + occurrences).sortedWith(compareBy({ it.course.name }, { it.course.source }, { it.date }))
    }
}
