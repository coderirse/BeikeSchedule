package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.ui.pet.ai.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PetAiContextTest {
    private val monday = LocalDate.of(2026, 11, 9)
    private val semester = SettingsStore.SemesterConfig(firstMonday = monday.toString())
    private val course = CourseEntity(id = 1, taskId = "c", name = "力学", teacher = "老师", location = "教101", dayOfWeek = 1,
        startSection = 1, endSection = 2, weekBitmap = "01", colorIndex = 0, source = CourseEntity.SOURCE_IMPORT)
    private val times = listOf(SectionTimeEntity(1, "08:00", "08:45"), SectionTimeEntity(2, "08:50", "09:35"))
    private fun rows(course: CourseEntity = this.course, config: SettingsStore.SemesterConfig = semester, date: LocalDate = monday) =
        PetLearningContext.courseData(listOf(course), times, config, listOf(date)).single().jsonObject["courses"]!!.jsonArray

    @Test fun dateToolMatchesCalendarAndUsesRealSectionTimes() {
        val row = rows().single().jsonObject
        assertEquals("力学", row["name"]!!.jsonPrimitive.content)
        assertEquals("08:00", row["start"]!!.jsonPrimitive.content)
        assertEquals("09:35", row["end"]!!.jsonPrimitive.content)
        assertTrue(rows(date = monday.plusDays(1)).isEmpty())
    }
    @Test fun hiddenSingleOccurrenceAndWholeCourseAreExcluded() {
        assertTrue(rows(course.copy(hidden = true)).isEmpty())
        assertTrue(rows(course.copy(hiddenDates = monday.toString())).isEmpty())
        assertEquals(1, rows(course.copy(hiddenDates = monday.plusDays(1).toString())).size)
    }
    @Test fun holidaysAndMakeupPriorityFollowExistingResolver() {
        val saturday = monday.plusDays(5)
        assertTrue(rows(config = semester.copy(holidays = listOf(monday.toString()))).isEmpty())
        assertEquals(1, rows(config = semester.copy(holidays = listOf(saturday.toString()), makeups = listOf("$saturday:1")), date = saturday).size)
    }
    @Test fun shortWeekBitmapsAndOutOfSemesterNeverInventClasses() {
        assertTrue(rows(course.copy(weekBitmap = "0")).isEmpty())
        assertTrue(rows(date = monday.minusWeeks(1)).isEmpty())
        assertTrue(rows(date = monday.plusWeeks(1)).isEmpty())
    }
}
