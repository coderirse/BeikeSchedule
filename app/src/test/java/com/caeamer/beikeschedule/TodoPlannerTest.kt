package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.model.TodoPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 日程重复规则展开、分组排序与提醒时刻计算的纯逻辑单测。 */
class TodoPlannerTest {

    // 2026-09-17 是周四（ISO dayOfWeek=4）
    private val thu = LocalDate.of(2026, 9, 17)
    private val fri = LocalDate.of(2026, 9, 18)
    private val sat = LocalDate.of(2026, 9, 19)
    private val sun = LocalDate.of(2026, 9, 20)
    private val mon = LocalDate.of(2026, 9, 21)

    private fun daily(time: String = "08:00", minutes: Int = 15) = TodoEntity(
        title = "晨读", time = time, repeatMode = TodoEntity.REPEAT_DAILY, remindMinutes = minutes,
    )

    private fun weekly(weekdays: String, time: String = "19:00") = TodoEntity(
        title = "晚自习", time = time, repeatMode = TodoEntity.REPEAT_WEEKLY, weekdays = weekdays,
    )

    private fun once(date: String, time: String = "10:00") = TodoEntity(
        title = "交报告", time = time, repeatMode = TodoEntity.REPEAT_ONCE, date = date,
    )

    // —— occursOn ——

    @Test
    fun `每天重复 每天都出现`() {
        assertTrue(TodoPlanner.occursOn(daily(), thu))
        assertTrue(TodoPlanner.occursOn(daily(), sun))
    }

    @Test
    fun `每周重复 只在勾选的天出现`() {
        // 0111110 = 周二~周六（索引 1..5），周四(索引3)与周五(索引4)出现，周日(索引6)不出现
        val w = weekly("0111110")
        assertTrue(TodoPlanner.occursOn(w, thu))
        assertTrue(TodoPlanner.occursOn(w, fri))
        assertFalse(TodoPlanner.occursOn(w, sun))   // 周日索引6 = '0'
        assertFalse(TodoPlanner.occursOn(w, mon))   // 周一索引0 = '0'
    }

    @Test
    fun `每周重复 位图越界视为不出现`() {
        val short = weekly("011") // 只到周三
        assertFalse(TodoPlanner.occursOn(short, thu)) // 周四索引3 越界
    }

    @Test
    fun `一次性 仅指定日期出现`() {
        val o = once("2026-09-18")
        assertTrue(TodoPlanner.occursOn(o, fri))
        assertFalse(TodoPlanner.occursOn(o, thu))
        assertFalse(TodoPlanner.occursOn(o, sat))
    }

    @Test
    fun `未知重复模式 不出现`() {
        val bad = daily().copy(repeatMode = 99)
        assertFalse(TodoPlanner.occursOn(bad, thu))
    }

    // —— upcomingOccurrences ——

    @Test
    fun `未来窗口 每天重复逐日列出`() {
        val occ = TodoPlanner.upcomingOccurrences(daily(), thu, 3)
        assertEquals(listOf(thu, fri, sat), occ.map { it.first })
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(8, 0), LocalTime.of(8, 0)), occ.map { it.second })
    }

    @Test
    fun `未来窗口 每周重复只列出勾选日`() {
        // 0111110 = 周二~周六：周四、周五、周六出现，周日/周一不出现
        val occ = TodoPlanner.upcomingOccurrences(weekly("0111110"), thu, 5)
        assertEquals(listOf(thu, fri, sat), occ.map { it.first })
    }

    @Test
    fun `未来窗口 一次性在窗口内只出现一次`() {
        val occ = TodoPlanner.upcomingOccurrences(once("2026-09-18"), thu, 5)
        assertEquals(listOf(fri), occ.map { it.first })
    }

    @Test
    fun `未来窗口 一次性在窗口外为空`() {
        val occ = TodoPlanner.upcomingOccurrences(once("2026-10-01"), thu, 5)
        assertTrue(occ.isEmpty())
    }

    @Test
    fun `时间格式非法 展开为空而不崩溃`() {
        val bad = daily(time = "not-a-time")
        assertTrue(TodoPlanner.upcomingOccurrences(bad, thu, 3).isEmpty())
    }

    // —— upcomingReminders ——

    @Test
    fun `提醒触发时刻 = 计划时刻减提前分钟`() {
        val todo = daily(time = "08:00", minutes = 20)
        val now = LocalDateTime.of(2026, 9, 17, 0, 0)
        val triggers = TodoPlanner.upcomingReminders(todo, thu, 1, now) // 仅今天
        assertEquals(listOf(LocalDateTime.of(2026, 9, 17, 7, 40)), triggers)
    }

    @Test
    fun `已过触发时刻不再补排`() {
        val todo = daily(time = "08:00", minutes = 15) // 触发于 07:45
        val now = LocalDateTime.of(2026, 9, 17, 12, 0)  // 今天中午，07:45 已过
        val triggers = TodoPlanner.upcomingReminders(todo, thu, 1, now) // 今天+明天
        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `今天已打卡 当天提醒不再排 未来日仍排`() {
        val todo = daily(time = "08:00", minutes = 10) // 触发于 07:50
        val now = LocalDateTime.of(2026, 9, 17, 0, 0)
        // 最后打卡日期=今天 → isDoneToday=true
        val done = todo.copy(lastDoneDate = "2026-09-17")
        val triggers = TodoPlanner.upcomingReminders(done, thu, 2, now)
        // 今天的不排，只剩明天 07:50
        assertEquals(listOf(LocalDateTime.of(2026, 9, 18, 7, 50)), triggers)
    }

    @Test
    fun `今天未打卡 当天提醒照排`() {
        val todo = daily(time = "08:00", minutes = 10)
        val now = LocalDateTime.of(2026, 9, 17, 0, 0)
        val triggers = TodoPlanner.upcomingReminders(todo, thu, 1, now) // 仅今天
        assertEquals(listOf(LocalDateTime.of(2026, 9, 17, 7, 50)), triggers)
    }

    @Test
    fun `重复事项的跨天提醒各自独立`() {
        val todo = daily(time = "08:00", minutes = 10)
        val now = LocalDateTime.of(2026, 9, 17, 0, 0)
        val triggers = TodoPlanner.upcomingReminders(todo, thu, 3, now) // 今天/明天/后天
        // 周四/五/六 各一条，均为当日 07:50
        assertEquals(3, triggers.size)
        assertEquals(LocalDateTime.of(2026, 9, 18, 7, 50), triggers[1])
    }

    // —— groupByDate ——

    @Test
    fun `分组按日期升序 日内按时间升序 空日期不出现`() {
        val todos = listOf(
            daily(time = "19:00"),                                  // 每天 19:00
            once("2026-09-18", time = "10:00"),                     // 仅周五 10:00
            weekly("1000000", time = "07:00"),                      // 仅周一 07:00
        )
        val groups = TodoPlanner.groupByDate(todos, thu, 5) // 周四~周一（含端点，共 5 天）
        assertEquals(listOf(thu, fri, sat, sun, mon), groups.map { it.first })
        // 周四：只有每天重复的 19:00
        assertEquals(listOf("19:00"), groups[0].second.map { it.time })
        // 周五：一次性 10:00 排在每天重复 19:00 前
        assertEquals(listOf("10:00", "19:00"), groups[1].second.map { it.time })
        // 周一：每周 07:00 + 每天 19:00
        assertEquals(listOf("07:00", "19:00"), groups[4].second.map { it.time })
    }

    @Test
    fun `分组 没有事项的日期被跳过`() {
        val todos = listOf(once("2026-09-18")) // 只有周五
        val groups = TodoPlanner.groupByDate(todos, thu, 3) // 周四~周六
        assertEquals(listOf(fri), groups.map { it.first })
    }

    // —— expiredOnce（已过期未完成的一次性事项）——

    @Test
    fun `过期 未完成的一次性事项在列`() {
        val todos = listOf(once("2026-09-10"), once("2026-09-18"))
        assertEquals(listOf("2026-09-10"), TodoPlanner.expiredOnce(todos, thu).map { it.date })
    }

    /**
     * 回归：补打卡写入的 lastDoneDate 是**今天**，永远不等于过去的 date。此前只判
     * `lastDoneDate != date`，于是过期区的勾选既不让条目消失、勾选框也恒显示未勾，
     * 成了一条点了没反应的无效交互。
     */
    @Test
    fun `过期 打卡后离开过期区`() {
        val todo = once("2026-09-10")
        assertEquals(1, TodoPlanner.expiredOnce(listOf(todo), thu).size)
        // 今天在过期区补打卡
        assertTrue(TodoPlanner.expiredOnce(listOf(todo.copy(lastDoneDate = thu.toString())), thu).isEmpty())
        // 当天按时完成过
        assertTrue(TodoPlanner.expiredOnce(listOf(todo.copy(lastDoneDate = todo.date)), thu).isEmpty())
    }

    @Test
    fun `过期 只收一次性事项_重复与未来与坏日期都不算`() {
        val todos = listOf(
            daily().copy(date = "2026-09-01"),
            weekly("1111100").copy(date = "2026-09-01"),
            once("2026-09-30"),
            once("日期坏了"),
        )
        assertTrue(TodoPlanner.expiredOnce(todos, thu).isEmpty())
    }

    @Test
    fun `每周重复默认位图点亮周一到周五`() {
        // 位图索引 0=周一（occursOn 用 dayOfWeek.value - 1 取位）：
        // 此前的默认值 "0111110" 按周日索引制才等于周一~五，在周一索引制下点亮的是周二~周六
        assertEquals("1111100", TodoEntity.DEFAULT_WEEKDAYS)
        val todo = weekly(TodoEntity.DEFAULT_WEEKDAYS)
        assertTrue(TodoPlanner.occursOn(todo, mon))
        assertTrue(TodoPlanner.occursOn(todo, thu))
        assertFalse(TodoPlanner.occursOn(todo, sat))
        assertFalse(TodoPlanner.occursOn(todo, sun))
    }
}
