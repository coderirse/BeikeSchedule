package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.ReminderCourses
import org.junit.Assert.assertEquals
import org.junit.Test

/** 上课提醒的课程筛选：排除无固定时间/隐藏课程，合并同名同段多行。 */
class ReminderCoursesTest {

    private fun course(
        id: Long,
        name: String = "课程$id",
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        unscheduled: Boolean = false,
        hidden: Boolean = false,
        weekBitmap: String = "0" + "1".repeat(20),
    ) = CourseEntity(
        id = id, taskId = "", name = name, teacher = "", location = "",
        dayOfWeek = if (unscheduled) 0 else day,
        startSection = if (unscheduled) 0 else start,
        endSection = if (unscheduled) 0 else end,
        weekBitmap = weekBitmap, colorIndex = 0,
        source = CourseEntity.SOURCE_IMPORT, hidden = hidden,
    )

    @Test
    fun `排除无固定时间课程`() {
        val result = ReminderCourses.eligible(
            listOf(course(1), course(2, unscheduled = true)),
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `排除已隐藏课程`() {
        val result = ReminderCourses.eligible(
            listOf(course(1), course(2, hidden = true)),
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `同名同段多行合并为一条`() {
        // 教务单双周拆行：同名同天同段两行，周次互补
        val result = ReminderCourses.eligible(
            listOf(
                course(1, name = "高数", weekBitmap = "0" + "10".repeat(10)),
                course(2, name = "高数", weekBitmap = "0" + "01".repeat(10)),
            ),
        )
        assertEquals(1, result.size)
        // 周次取并集：1-20 周全有课
        assertEquals("0" + "1".repeat(20), result.first().weekBitmap)
    }

    @Test
    fun `不同课程不合并`() {
        val result = ReminderCourses.eligible(listOf(course(1, name = "高数"), course(2, name = "英语")))
        assertEquals(2, result.size)
    }

    @Test
    fun `同名但不同时段不合并`() {
        val result = ReminderCourses.eligible(
            listOf(course(1, name = "高数", start = 1), course(2, name = "高数", start = 3)),
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `空列表返回空`() {
        assertEquals(emptyList<CourseEntity>(), ReminderCourses.eligible(emptyList()))
    }
}
