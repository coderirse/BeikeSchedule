package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 当前周推算单测。 */
class CurrentWeekTest {

    @Test
    fun `开学当天为第 1 周`() {
        val week = ScheduleRepository.currentWeek("2026-09-07", 20, LocalDate.of(2026, 9, 7))
        assertEquals(1, week)
    }

    @Test
    fun `周日仍属于当周`() {
        // 2026-09-13 是第 1 周周日
        val week = ScheduleRepository.currentWeek("2026-09-07", 20, LocalDate.of(2026, 9, 13))
        assertEquals(1, week)
    }

    @Test
    fun `跨周计算正确`() {
        // 2026-09-21 是第 3 周周一
        val week = ScheduleRepository.currentWeek("2026-09-07", 20, LocalDate.of(2026, 9, 21))
        assertEquals(3, week)
    }

    @Test
    fun `超出学期范围返回 null`() {
        assertNull(ScheduleRepository.currentWeek("2026-09-07", 20, LocalDate.of(2027, 3, 1)))
        assertNull(ScheduleRepository.currentWeek("2026-09-07", 20, LocalDate.of(2026, 8, 1)))
    }

    @Test
    fun `空日期或非法日期返回 null`() {
        assertNull(ScheduleRepository.currentWeek("", 20, LocalDate.of(2026, 9, 7)))
        assertNull(ScheduleRepository.currentWeek("not-a-date", 20, LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun `开学日期非周一时对齐到上周一`() {
        // 2026-09-09 是周三，应对齐到 09-07 周一
        val week = ScheduleRepository.currentWeek("2026-09-09", 20, LocalDate.of(2026, 9, 14))
        assertEquals(2, week)
    }

    // —— 官方教学周日历 locateWeek ——

    /** 2026-2027-1 真实校历：第 3 周 9/21，国庆周跳过，第 4 周 10/5。 */
    private val realCalendar = listOf(
        "2026-09-07", "2026-09-14", "2026-09-21", "2026-10-05",
        "2026-10-12", "2026-10-19", "2026-10-26",
    )

    @Test
    fun `官方日历 普通教学周定位`() {
        val loc = ScheduleRepository.locateWeek(realCalendar, LocalDate.of(2026, 9, 23))
        assertEquals(3, loc.week)
        assertEquals(false, loc.isHoliday)
    }

    @Test
    fun `官方日历 第4周从10月5日开始而非9月28日`() {
        val loc = ScheduleRepository.locateWeek(realCalendar, LocalDate.of(2026, 10, 5))
        assertEquals(4, loc.week)
        assertEquals(false, loc.isHoliday)
    }

    @Test
    fun `官方日历 国庆周识别为假期并指向下一教学周`() {
        // 2026-10-01 在被跳过的国庆周里
        val loc = ScheduleRepository.locateWeek(realCalendar, LocalDate.of(2026, 10, 1))
        assertEquals(4, loc.week)
        assertEquals(true, loc.isHoliday)
        assertEquals("2026-10-05", loc.nextWeekMonday)
    }

    @Test
    fun `官方日历 未开学视为第1周`() {
        val loc = ScheduleRepository.locateWeek(realCalendar, LocalDate.of(2026, 8, 28))
        assertEquals(1, loc.week)
        assertEquals(false, loc.isHoliday)
        // 状态文案语义：开学前必须能区分出来（v1.1.1 曾因此误显示"第1周"）
        assertEquals(true, loc.beforeStart)
    }

    @Test
    fun `官方日历 学期结束后 week 为 null 且标记 afterEnd`() {
        val loc = ScheduleRepository.locateWeek(realCalendar, LocalDate.of(2026, 11, 5))
        assertNull(loc.week)
        assertEquals(false, loc.isHoliday)
        assertEquals(true, loc.afterEnd)
        assertEquals(false, loc.beforeStart)
    }

    @Test
    fun `官方日历 开学当天不算 beforeStart`() {
        // 9/7 是第 1 周周一，当天已是学期内
        val loc = ScheduleRepository.locateWeek(realCalendar, LocalDate.of(2026, 9, 7))
        assertEquals(1, loc.week)
        assertEquals(false, loc.beforeStart)
    }

    @Test
    fun `官方日历 空日历回退 week 为 null`() {
        val loc = ScheduleRepository.locateWeek(emptyList(), LocalDate.of(2026, 9, 21))
        assertNull(loc.week)
        assertEquals(false, loc.beforeStart)
        assertEquals(false, loc.afterEnd)
    }

    // —— 严格教学周判定 teachingWeekOf（提醒排期用）——

    @Test
    fun `严格判定 开学前返回 null 不排提醒`() {
        // 2026-08-31 在开学（9/7）之前，显示语义算第1周，但严格判定无课
        assertNull(ScheduleRepository.teachingWeekOf(realCalendar, LocalDate.of(2026, 8, 31)))
    }

    @Test
    fun `严格判定 假期跳周返回 null`() {
        assertNull(ScheduleRepository.teachingWeekOf(realCalendar, LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `严格判定 教学周内返回周号`() {
        assertEquals(4, ScheduleRepository.teachingWeekOf(realCalendar, LocalDate.of(2026, 10, 8)))
        assertEquals(1, ScheduleRepository.teachingWeekOf(realCalendar, LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun `严格判定 学期结束后返回 null`() {
        assertNull(ScheduleRepository.teachingWeekOf(realCalendar, LocalDate.of(2026, 11, 5)))
    }
}
