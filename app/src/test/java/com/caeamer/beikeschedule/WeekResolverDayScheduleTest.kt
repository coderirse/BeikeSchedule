package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.WeekResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 天级校历（放假）与调休补课的日期→上课计划解析。
 *
 * 数据背景（2026-10-05 实抓教务校历）：2026-2027-1 学期第 1-3 周周一为 9/7、9/14、9/21，
 * 国庆假期周（9/28-10/4）不占教学周序号，第 4 周周一 = 10/5；工作日放假标记共 9 天
 * （9/25 中秋、9/28-10/2、10/5-10/7）；10/10（周六）补周三的课。
 */
class WeekResolverDayScheduleTest {

    /** 官方校历：18 个教学周周一，第 4 周跳过 9/28 那一周。 */
    private val weekMondays: List<String> = buildList {
        var d = LocalDate.of(2026, 9, 7)
        repeat(18) { i ->
            if (i == 3) d = d.plusWeeks(1)
            add(d.toString())
            d = d.plusWeeks(1)
        }
    }

    /** 本学期真实放假口径（仅工作日）。 */
    private val holidays = listOf(
        "2026-09-25", "2026-09-28", "2026-09-29", "2026-09-30",
        "2026-10-01", "2026-10-02", "2026-10-05", "2026-10-06", "2026-10-07",
    )

    private val semester = SettingsStore.SemesterConfig(
        xn = "2026-2027", xq = "1", name = "2026-2027-1",
        firstMonday = "2026-09-07", totalWeeks = 18,
        weekMondays = weekMondays, holidays = holidays,
    )

    @Test
    fun `教学周内的放假日_周次正常但标记假期`() {
        val plan = WeekResolver.daySchedule(semester, LocalDate.of(2026, 10, 5))
        assertEquals(4, plan.week)
        assertEquals(1, plan.coursesDayOfWeek)
        assertTrue(plan.holiday)
        assertFalse(plan.makeup)
    }

    @Test
    fun `单日假期_中秋`() {
        val plan = WeekResolver.daySchedule(semester, LocalDate.of(2026, 9, 25))
        assertEquals(3, plan.week)
        assertEquals(5, plan.coursesDayOfWeek)
        assertTrue(plan.holiday)
    }

    @Test
    fun `假期跳周内_无周次且为假期`() {
        // 9/29 落在国庆假期周（不占教学周序号）
        val plan = WeekResolver.daySchedule(semester, LocalDate.of(2026, 9, 29))
        assertNull(plan.week)
        assertTrue(plan.holiday)
    }

    @Test
    fun `恢复上课日_正常上课`() {
        val plan = WeekResolver.daySchedule(semester, LocalDate.of(2026, 10, 8))
        assertEquals(4, plan.week)
        assertEquals(4, plan.coursesDayOfWeek)
        assertFalse(plan.holiday)
    }

    @Test
    fun `周末天然非假期标记`() {
        // 10/11（周日）不在 holidays 里（周末天然无课，无需标记）
        val plan = WeekResolver.daySchedule(semester, LocalDate.of(2026, 10, 11))
        assertEquals(4, plan.week)
        assertFalse(plan.holiday)
    }

    @Test
    fun `存量补课配置与内置数据合并生效`() {
        // 10/10（周六，第 4 周）补周三的课（存量配置路径，云恢复兼容）
        val withMakeup = semester.copy(makeups = listOf("2026-10-10:3"))
        val plan = WeekResolver.daySchedule(withMakeup, LocalDate.of(2026, 10, 10))
        assertEquals(4, plan.week)
        assertEquals(3, plan.coursesDayOfWeek)
        assertTrue(plan.makeup)
        assertFalse(plan.holiday)
    }

    @Test
    fun `内置补课数据自动生效_无需登记`() {
        // xn/xq 命中 SchoolAdjustments 内置表（2026-2027-1：10/10 补周三），无存量配置也生效
        val plan = WeekResolver.daySchedule(semester, LocalDate.of(2026, 10, 10))
        assertEquals(4, plan.week)
        assertEquals(3, plan.coursesDayOfWeek)
        assertTrue(plan.makeup)
        assertFalse(plan.holiday)
    }

    @Test
    fun `无内置数据的学期周六保持自然星期`() {
        val other = semester.copy(xn = "other-school", xq = "2")
        val plan = WeekResolver.daySchedule(other, LocalDate.of(2026, 10, 10))
        assertEquals(6, plan.coursesDayOfWeek)
        assertFalse(plan.makeup)
    }

    @Test
    fun `补课优先于假期标记`() {
        // 校历把某天标成放假、用户又配置了补课（少见但可能打架）：按补课执行
        val both = semester.copy(
            holidays = holidays + "2026-10-10",
            makeups = listOf("2026-10-10:3"),
        )
        val plan = WeekResolver.daySchedule(both, LocalDate.of(2026, 10, 10))
        assertFalse(plan.holiday)
        assertTrue(plan.makeup)
        assertEquals(3, plan.coursesDayOfWeek)
    }

    @Test
    fun `无校历时按开学日期推算且假期标记仍生效`() {
        val plain = SettingsStore.SemesterConfig(
            firstMonday = "2026-09-07", totalWeeks = 18, holidays = listOf("2026-10-05"),
        )
        val plan = WeekResolver.daySchedule(plain, LocalDate.of(2026, 10, 5))
        assertEquals(5, plan.week) // firstMonday 推算不知道跳周，10/5 落第 5 周
        assertTrue(plan.holiday)
    }

    @Test
    fun `坏数据逐条丢弃不整体作废`() {
        assertTrue(WeekResolver.parseHolidays(listOf("2026-10-05", "not-a-date")).size == 1)
        assertTrue(WeekResolver.parseHolidays(listOf(" 2026-10-06 ")).size == 1)
        assertEquals(3, WeekResolver.parseMakeups(listOf("2026-10-10:3"))[LocalDate.of(2026, 10, 10)])
        assertEquals(0, WeekResolver.parseMakeups(listOf("bad-date:3")).size)
        assertEquals(0, WeekResolver.parseMakeups(listOf("2026-10-10:0")).size)
        assertEquals(0, WeekResolver.parseMakeups(listOf("2026-10-10:8")).size)
        assertEquals(0, WeekResolver.parseMakeups(listOf("2026-10-10")).size)
    }
}
