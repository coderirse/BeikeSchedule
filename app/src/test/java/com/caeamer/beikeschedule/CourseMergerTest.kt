package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseMerger
import org.junit.Assert.assertEquals
import org.junit.Test

/** 同名同段多行合并单测（教务单周调课/单双周拆分行）。 */
class CourseMergerTest {

    private fun course(
        name: String,
        day: Int = 3,
        start: Int = 3,
        end: Int = 4,
        zc: String,
        location: String = "机械楼720",
        teacher: String = "韩天",
    ) = CourseEntity(
        taskId = "RWH1", name = name, teacher = teacher, location = location,
        dayOfWeek = day, startSection = start, endSection = end,
        weekBitmap = zc, colorIndex = 1, source = CourseEntity.SOURCE_IMPORT,
    )

    @Test
    fun `同名同段 - 周次并集`() {
        // 真实场景：机电传动控制 1-6周 + 7周调课行 + 8周调课行（地点"-"）
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("机电传动控制", zc = "0111111000000000000000000000000000"),
                course("机电传动控制", zc = "0000000100000000000000000000000000", location = "-"),
                course("机电传动控制", zc = "0000000010000000000000000000000000", location = "-"),
            ),
        )
        assertEquals(1, merged.size)
        // 1-8 周都亮
        assertEquals("0111111110000000000000000000000000", merged[0].weekBitmap)
        // 基准行保留完整地点，不取调课行的 "-"
        assertEquals("机械楼720", merged[0].location)
    }

    @Test
    fun `单双周拆分 - 合并后每周都亮`() {
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("高数", zc = "0101010100000000000000000000000000"),
                course("高数", zc = "0010101010000000000000000000000000"),
            ),
        )
        assertEquals(1, merged.size)
        (1..8).forEach { w -> assertEquals(true, merged[0].hasClassOnWeek(w)) }
        assertEquals(false, merged[0].hasClassOnWeek(9))
    }

    @Test
    fun `不同课程同时段 - 不合并`() {
        val merged = CourseMerger.mergeSameSlot(
            listOf(course("机电传动控制", zc = "0111111000000000000000000000000000"), course("工程材料", zc = "0000000110000000000000000000000000")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `同名不同段 - 不合并`() {
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("电子技术实习C", start = 9, end = 12, zc = "0111111000000000000000000000000000"),
                course("电子技术实习C", start = 1, end = 2, zc = "0000000010000000000000000000000000"),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `单行 - 原样返回`() {
        val merged = CourseMerger.mergeSameSlot(listOf(course("机械设计", zc = "0111111000000000000000000000000000")))
        assertEquals(1, merged.size)
        assertEquals("机械楼720", merged[0].location)
    }
}
