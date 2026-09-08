package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.NextClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 下一节课解析：只看今天、跳过已开始、严格教学周内。 */
class NextClassTest {

    private val times = mapOf(
        1 to "08:00", 2 to "08:50", 3 to "09:55", 4 to "10:50",
        5 to "13:30", 6 to "14:20", 7 to "15:20", 8 to "16:10",
        9 to "17:10", 10 to "18:00", 11 to "19:30", 12 to "20:20",
    )

    private fun course(
        id: Long,
        day: Int,
        start: Int,
        end: Int = start + 1,
        weekBitmap: String = "0" + "1".repeat(20),
    ) = CourseEntity(
        id = id, taskId = "", name = "课程$id", teacher = "", location = "",
        dayOfWeek = day, startSection = start, endSection = end,
        weekBitmap = weekBitmap, colorIndex = 0, source = CourseEntity.SOURCE_IMPORT,
    )

    /** 2026-09-07 是周一。 */
    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    @Test
    fun `今天未开始的最早一节被选中`() {
        val courses = listOf(
            course(1, day = 1, start = 1),   // 08:00 已过
            course(2, day = 1, start = 3),   // 09:55 下一节
            course(3, day = 1, start = 5),   // 13:30 更晚
        )
        val target = NextClass.resolve(courses, times, 1, monday.atTime(9, 0))
        assertEquals(2L, target?.courseId)
        assertEquals("09:55", target?.startTime)
    }

    @Test
    fun `正在进行的课不算下一节`() {
        val courses = listOf(
            course(1, day = 1, start = 1, end = 2), // 08:00 正在上
            course(2, day = 1, start = 3),          // 09:55 下一节
        )
        val target = NextClass.resolve(courses, times, 1, monday.atTime(8, 30))
        assertEquals(2L, target?.courseId)
    }

    @Test
    fun `恰在上课时刻也算已开始`() {
        val courses = listOf(course(1, day = 1, start = 1), course(2, day = 1, start = 3))
        val target = NextClass.resolve(courses, times, 1, monday.atTime(8, 0))
        assertEquals(2L, target?.courseId)
    }

    @Test
    fun `今天课上完不跨天指向明天`() {
        val courses = listOf(course(1, day = 1, start = 1), course(2, day = 2, start = 1))
        assertNull(NextClass.resolve(courses, times, 1, monday.atTime(21, 0)))
    }

    @Test
    fun `非今天课程不参与`() {
        val courses = listOf(course(1, day = 2, start = 1), course(2, day = 3, start = 1))
        assertNull(NextClass.resolve(courses, times, 1, monday.atTime(7, 0)))
    }

    @Test
    fun `今天该周无课则不标记`() {
        // 位图只有第 2 周有课，今天第 1 周
        val bitmap = "0" + "0" + "1" + "0".repeat(18)
        val courses = listOf(course(1, day = 1, start = 3, weekBitmap = bitmap))
        assertNull(NextClass.resolve(courses, times, 1, monday.atTime(7, 0)))
    }

    @Test
    fun `假期或学期外不标记`() {
        val courses = listOf(course(1, day = 1, start = 3))
        assertNull(NextClass.resolve(courses, times, null, monday.atTime(7, 0)))
    }

    @Test
    fun `无固定时间课程不参与`() {
        val courses = listOf(course(1, day = 0, start = 0, end = 0))
        assertNull(NextClass.resolve(courses, times, 1, monday.atTime(7, 0)))
    }

    @Test
    fun `节次时间缺失的课被跳过不阻断其他候选`() {
        val courses = listOf(course(1, day = 1, start = 99), course(2, day = 1, start = 3))
        val target = NextClass.resolve(courses, times, 1, monday.atTime(7, 0))
        assertEquals(2L, target?.courseId)
    }

    @Test
    fun `周日也能正确匹配`() {
        val sunday = monday.plusDays(6)
        val courses = listOf(course(1, day = 7, start = 3))
        val target = NextClass.resolve(courses, times, 1, sunday.atTime(7, 0))
        assertEquals(1L, target?.courseId)
        assertEquals(7, target?.dayOfWeek)
    }
}
