package com.example.beikeschedule

import com.example.beikeschedule.model.WeekUtils
import org.junit.Assert.assertEquals
import org.junit.Test

/** 周次位图工具单测。 */
class WeekUtilsTest {

    @Test
    fun `buildWeekBitmap - 连续周`() {
        val bitmap = WeekUtils.buildWeekBitmap(1, 8, WeekUtils.WEEK_TYPE_ALL, 20)
        assertEquals(21, bitmap.length) // 0 号位占位 + 20 周
        assertEquals('0', bitmap[0])
        assertEquals((1..20).map { if (it <= 8) '1' else '0' }.joinToString(""), bitmap.substring(1))
    }

    @Test
    fun `buildWeekBitmap - 单双周`() {
        val odd = WeekUtils.buildWeekBitmap(1, 16, WeekUtils.WEEK_TYPE_ODD, 20)
        assertEquals("单", WeekUtils.oddEvenLabel(odd))
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), WeekUtils.weeksOf(odd))

        val even = WeekUtils.buildWeekBitmap(1, 16, WeekUtils.WEEK_TYPE_EVEN, 20)
        assertEquals("双", WeekUtils.oddEvenLabel(even))
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), WeekUtils.weeksOf(even))
    }

    @Test
    fun `describe - 连续区间`() {
        val bitmap = WeekUtils.buildWeekBitmap(1, 8, WeekUtils.WEEK_TYPE_ALL, 20)
        assertEquals("1-8周", WeekUtils.describe(bitmap))
    }

    @Test
    fun `describe - 间断区间`() {
        // 1-3 周 + 5-8 周有课
        val bitmap = "0" + "1110" + "1111" + "0".repeat(12)
        assertEquals("1-3,5-8周", WeekUtils.describe(bitmap))
    }

    @Test
    fun `describe - 单周标注`() {
        val bitmap = WeekUtils.buildWeekBitmap(1, 10, WeekUtils.WEEK_TYPE_ODD, 20)
        assertEquals("1,3,5,7,9周 单周", WeekUtils.describe(bitmap))
    }

    @Test
    fun `describe - 空位图`() {
        assertEquals("无周次", WeekUtils.describe("0".repeat(21)))
    }
}
