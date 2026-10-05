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
     * **今天已打卡的不排**：打卡的意图就是"这件事不用再提醒了"；未来日不受打卡影响
     * （重复任务次日自然复活）。返回按触发时刻升序，供调度器直接落闹钟。
     */
    fun upcomingReminders(
        todo: TodoEntity,
        today: LocalDate,
        days: Int,
        now: LocalDateTime,
    ): List<LocalDateTime> {
        val minutes = todo.remindMinutes.coerceAtLeast(0).toLong()
        return upcomingOccurrences(todo, today, days)
            .filter { (date, _) -> !(date == today && todo.isDoneToday(today.toString())) }
            .map { (date, time) -> LocalDateTime.of(date, time).minusMinutes(minutes) }
            .filter { it.isAfter(now) }
            .sorted()
    }

    /**
     * 已过期的一次性事项（日期在过去、且没打过卡），按日期升序。
     *
     * 主列表只展示今天起 [days] 天，没有这个出口它们就成了"幽灵数据"——永远不可见、
     * 不可编辑、不可删，却仍参与提醒重排。
     *
     * **打过卡即离开过期区**：`lastDoneDate == date` 是当天按时完成过，`lastDoneDate == today`
     * 是用户刚在过期区补打了卡。此前只判前者，而补打卡写入的是今天 → 永远不等于过去的 date，
     * 条目既不消失、勾选框也恒显示未勾，成了点了没反应的无效交互。
     */
    fun expiredOnce(todos: List<TodoEntity>, today: LocalDate): List<TodoEntity> =
        todos.filter { todo ->
            todo.repeatMode == TodoEntity.REPEAT_ONCE &&
                runCatching { LocalDate.parse(todo.date) }.getOrNull()?.isBefore(today) == true &&
                todo.lastDoneDate != todo.date &&
                todo.lastDoneDate != today.toString()
        }.sortedBy { it.date }

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
                // 与 upcomingReminders 同口径先 parse 再排序：非零填充的 "9:00" 按
                // 字符串排会落到 "10:00" 之后；解析失败的沉底
                val dayTodos = todos.filter { occursOn(it, date) }
                    .sortedBy { runCatching { LocalTime.parse(it.time) }.getOrDefault(LocalTime.MAX) }
                if (dayTodos.isEmpty()) null else date to dayTodos
            }
}
