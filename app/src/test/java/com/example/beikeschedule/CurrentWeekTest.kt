package com.example.beikeschedule

import com.example.beikeschedule.data.repo.ScheduleRepository
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
}
