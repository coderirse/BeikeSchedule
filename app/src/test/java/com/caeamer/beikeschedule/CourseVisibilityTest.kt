package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.CourseMerger
import com.caeamer.beikeschedule.model.CourseVisibility
import com.caeamer.beikeschedule.model.NextClass
import com.caeamer.beikeschedule.model.WeekLayout
import com.caeamer.beikeschedule.model.WeekResolver
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CourseVisibilityTest {
    private val date = LocalDate.of(2026, 11, 5)
    private val semester = SettingsStore.SemesterConfig(firstMonday = "2026-09-14", totalWeeks = 20)
    private fun course(id: Long = 1, day: Int = 4, start: Int = 7) = CourseEntity(
        id = id, taskId = "T1", name = "工程数值计算（上机）", teacher = "教师", location = "机械楼517",
        dayOfWeek = day, startSection = start, endSection = start + 1,
        weekBitmap = "0" + "1".repeat(20), colorIndex = 0, source = CourseEntity.SOURCE_IMPORT,
    )

    @Test fun `only the clicked date and session disappear`() {
        val hidden = course().copy(hiddenDates = date.toString())
        val monday = course(2, day = 1)
        val later = course(3, start = 9)
        val courses = listOf(hidden, monday, later)
        assertEquals(listOf(2L, 3L), CourseVisibility.onDate(courses, date).map { it.id })
        assertEquals(3, CourseVisibility.onDate(courses, date.plusWeeks(1)).size)
        assertEquals(hidden.weekBitmap, course().weekBitmap)
        // 主动隐藏不能退化成本周不上课的淡化卡片。
        val layout = WeekLayout.layoutDay(CourseVisibility.onDate(courses, date), 4, 8, false)
        assertEquals(listOf(3L), layout.clusters.flatten().map { it.id })
        assertTrue(layout.inactives.isEmpty())
    }

    @Test fun `split rows disappear together and recovery is one entry`() {
        val a = course().copy(weekBitmap = "010101010", hiddenDates = date.toString())
        val b = course(2).copy(weekBitmap = "001010101", hiddenDates = date.toString())
        val rows = listOf(a, b)
        assertTrue(CourseVisibility.onDate(rows, date).isEmpty())
        val entry = CourseVisibility.hiddenEntries(rows).single()
        assertEquals(listOf(1L, 2L), entry.ids)
        assertEquals(date, entry.date)
        val restored = rows.map { it.copy(hiddenDates = CourseVisibility.withDate(it, date, false)) }
        assertEquals(1, CourseVisibility.onDate(restored, date).size)
        assertTrue(CourseVisibility.hiddenEntries(restored).isEmpty())
    }

    @Test fun `restoring one occurrence retains other dates`() {
        val c = course().copy(hiddenDates = "$date,${date.plusWeeks(1)}")
        val restored = c.copy(hiddenDates = CourseVisibility.withDate(c, date, false))
        assertTrue(CourseVisibility.visibleOn(restored, date))
        assertFalse(CourseVisibility.visibleOn(restored, date.plusWeeks(1)))
        assertEquals(restored.hiddenDates, CourseVisibility.withDate(restored, date.plusWeeks(1), true))
    }

    @Test fun `legacy whole semester rows group by name and source`() {
        val rows = listOf(course().copy(hidden = true), course(2, day = 1).copy(hidden = true),
            course(3).copy(source = CourseEntity.SOURCE_MANUAL, hidden = true))
        val entries = CourseVisibility.hiddenEntries(rows)
        assertEquals(2, entries.size)
        assertEquals(listOf(1L, 2L), entries.first().ids)
        assertTrue(entries.all { it.date == null })
        assertTrue(CourseVisibility.onDate(rows, date).isEmpty())
    }

    @Test fun `card row selection respects location and session groups`() {
        val a = course()
        val b = course(2).copy(location = "-", weekBitmap = "000000001")
        val otherRoom = course(3).copy(location = "教学楼403")
        val otherTime = course(4, start = 9)
        val rows = listOf(a, b, otherRoom, otherTime)
        val card = CourseMerger.mergeSameSlot(rows).first()
        assertEquals(listOf(1L, 2L), CourseMerger.rowsForCard(rows, card).map { it.id })
    }

    @Test fun `reimport preserves occurrences across new ids and split week rows`() {
        val old = course().copy(hiddenDates = date.toString())
        val incoming = listOf(course(11).copy(weekBitmap = "010101010"),
            course(12).copy(weekBitmap = "001010101"), course(13, day = 1),
            course(14).copy(taskId = "NEW_TERM_TASK"))
        val result = CourseVisibility.preserveDates(listOf(old), incoming)
        assertEquals(listOf(date.toString(), date.toString(), "", ""), result.map { it.hiddenDates })
        assertEquals(incoming.map { it.weekBitmap }, result.map { it.weekBitmap })
    }

    @Test fun `malformed stored dates do not break schedule`() {
        val c = course().copy(hiddenDates = ",bad-date,$date,2026-13-01")
        assertEquals(setOf(date), CourseVisibility.dates(c))
        assertFalse(CourseVisibility.visibleOn(c, date))
    }

    @Test fun `reimport does not spread hidden placeholder dates to a different room`() {
        val a = course().copy(hiddenDates = date.toString())
        val placeholder = course(2).copy(location = "-", hiddenDates = date.toString())
        val b = course(3).copy(location = "教学楼403")
        val previous = listOf(a, placeholder, b)
        val incoming = previous.map { it.copy(id = it.id + 10, hiddenDates = "") }
        val restored = CourseVisibility.preserveDates(previous, incoming)
        assertEquals(listOf(date.toString(), date.toString(), ""), restored.map { it.hiddenDates })
        assertEquals(listOf(13L), CourseVisibility.onDate(restored, date).map { it.id })
    }

    @Test fun `makeup date can be hidden without hiding the original weekday`() {
        val makeup = date.plusDays(2)
        val config = semester.copy(makeups = listOf("$makeup:4"))
        val hidden = course().copy(hiddenDates = makeup.toString())
        assertEquals(4, WeekResolver.daySchedule(config, makeup).coursesDayOfWeek)
        assertTrue(CourseVisibility.onDate(listOf(hidden), makeup).isEmpty())
        assertEquals(listOf(hidden), CourseVisibility.onDate(listOf(hidden), date))
    }

    @Test fun `reminders skip only hidden occurrence and do not duplicate split rows`() {
        val rows = listOf(course().copy(hiddenDates = date.toString()),
            course(2).copy(hiddenDates = date.toString()))
        val planned = ClassReminderScheduler.planClassReminders(
            rows, mapOf(7 to "15:20"), semester, 5, date.atStartOfDay(), ZoneId.of("Asia/Hong_Kong"),
        )
        assertEquals(1, planned.size) // 8 天窗口只留下下周四，而且拆行只提醒一次。
        val expected = date.plusWeeks(1).atTime(15, 15).atZone(ZoneId.of("Asia/Hong_Kong")).toInstant().toEpochMilli()
        assertEquals(expected, planned.single().triggerAtMillis)
    }

    @Test fun `next class skips hidden occurrence but returns it after restore`() {
        val first = course().copy(hiddenDates = date.toString())
        val later = course(2, start = 9)
        val now = LocalDateTime.of(date, java.time.LocalTime.of(12, 0))
        val plan = WeekResolver.daySchedule(semester, date)
        val starts = mapOf(7 to "15:20", 9 to "17:10")
        assertEquals(2L, NextClass.resolve(listOf(first, later), starts, plan, now)?.courseId)
        assertEquals(1L, NextClass.resolve(listOf(first.copy(hiddenDates = ""), later), starts, plan, now)?.courseId)
    }
}
