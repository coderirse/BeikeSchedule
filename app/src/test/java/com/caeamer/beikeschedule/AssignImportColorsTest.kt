package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.CourseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 教务课程颜色去重逻辑单测。 */
class AssignImportColorsTest {

    private fun course(name: String, xb: Int, unscheduled: Boolean = false) =
        CourseEntity(
            taskId = name, name = name, teacher = "", location = "",
            dayOfWeek = if (unscheduled) 0 else 1,
            startSection = if (unscheduled) 0 else 1,
            endSection = if (unscheduled) 0 else 2,
            weekBitmap = "01110", colorIndex = xb, source = CourseEntity.SOURCE_IMPORT,
        )

    @Test
    fun `同一门课多时段共享同一颜色`() {
        val result = ScheduleRepository.assignImportColors(
            listOf(course("机械设计", 3), course("机械设计", 3)),
        )
        assertEquals(result[0].colorIndex, result[1].colorIndex)
    }

    @Test
    fun `无固定时间课程颜色不重复`() {
        // 两门无固定时间课程，原来用 name.hashCode() 可能撞色，现在分配唯一色
        val result = ScheduleRepository.assignImportColors(
            listOf(
                course("电子技术实验", CourseEntity.COLOR_UNSCHEDULED, unscheduled = true),
                course("形势与政策5", CourseEntity.COLOR_UNSCHEDULED, unscheduled = true),
                course("毛泽东思想和中国特色社会主义理论体系概论", CourseEntity.COLOR_UNSCHEDULED, unscheduled = true),
            ),
        )
        val colors = result.map { it.colorIndex }.toSet()
        assertEquals(3, colors.size) // 三门课三种颜色，不撞色
    }

    @Test
    fun `教务原始色值在色板内优先保留`() {
        val result = ScheduleRepository.assignImportColors(
            listOf(course("概率论", 1), course("体育", 1)),
        )
        // 概率论拿到 1，体育同原始色但被占用 → 顺延不同色
        assertNotEquals(result[0].colorIndex, result[1].colorIndex)
    }
}
