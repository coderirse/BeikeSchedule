package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.TodoEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 日程重复规则的纯函数展开：给定事项与日期/时间，判定是否出现、算出提醒触发时刻。
 * 全部无 Android 依赖，可直接 JVM 单测。
 */
object TodoPlanner {

    /**
     * 该事项在 [date] 这一天是否出现。
     *
     * - 每天重复：恒 true；
     * - 每周重复：[TodoEntity.weekdays] 位图按 ISO 星期（周一=1…周日=7）取位；
     *   位图长度不足/下标越界一律视为不出现（宁可漏显也不误显）；
     * - 一次性：仅当 [TodoEntity.date] 等于 [date]。
     */
    fun occursOn(todo: TodoEntity, date: LocalDate): Boolean = when (todo.repeatMode) {
        TodoEntity.REPEAT_DAILY -> true
        TodoEntity.REPEAT_WEEKLY -> {
            val idx = date.dayOfWeek.value - 1 // 周一=0 … 周日=6
            idx in todo.weekdays.indices && todo.weekdays[idx] == '1'
        }
        TodoEntity.REPEAT_ONCE -> todo.date.isNotBlank() && todo.date == date.toString()
        else -> false
    }

    /**
     * 该事项未来 [days] 天内每个出现日的计划时刻。
     *
     * "未来 N 天"= 今天起连续 N 天（含今天），日期区间 **[today, today + days - 1]**。
     * 返回 (date, time) 列表，按日期升序；[time] 解析失败（格式异常）时跳过该事项 ——
     * 一个时间串坏了不该让所有日程的整体排期失效。
     */
    fun upcomingOccurrences(
        todo: TodoEntity,
        today: LocalDate,
        days: Int,
    ): List<Pair<LocalDate, LocalTime>> {
        val time = runCatching { LocalTime.parse(todo.time) }.getOrNull() ?: return emptyList()
        return (0 until days)
            .map { today.plusDays(it.toLong()) }
            .filter { occursOn(todo, it) }
            .map { it to time }
    }

    /**
     * 未来 [days] 天内要排的提醒（触发时刻 = 计划时刻 − 提前分钟数）。
     *
     * 只排触发时刻仍在 [now] 之后的：过去的不再补排，避免一打开 App 就补一堆过期提醒。
     * 返回按触发时刻升序，供调度器直接落闹钟。
     */
    fun upcomingReminders(
        todo: TodoEntity,
        today: LocalDate,
        days: Int,
        now: LocalDateTime,
    ): List<LocalDateTime> {
        val minutes = todo.remindMinutes.coerceAtLeast(0).toLong()
        return upcomingOccurrences(todo, today, days)
            .map { (date, time) -> LocalDateTime.of(date, time).minusMinutes(minutes) }
            .filter { it.isAfter(now) }
            .sorted()
    }

    /**
     * 给一段日期范围分组展示用的"日期 → 当日事项"分组，按日期升序、日内按时间升序。
     *
     * 仅含**确实有事项出现**的日期（空日期不出现在结果里）。
     */
    fun groupByDate(
        todos: List<TodoEntity>,
        today: LocalDate,
        days: Int,
    ): List<Pair<LocalDate, List<TodoEntity>>> =
        (0 until days)
            .map { today.plusDays(it.toLong()) }
            .mapNotNull { date ->
                val dayTodos = todos.filter { occursOn(it, date) }.sortedBy { it.time }
                if (dayTodos.isEmpty()) null else date to dayTodos
            }
}
