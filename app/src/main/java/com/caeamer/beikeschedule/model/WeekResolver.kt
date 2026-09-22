package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import java.time.LocalDate

/**
 * 学期周次定位的**唯一实现**。
 *
 * 此前同一套逻辑在三处各写了一份，且都带同一个缺陷：
 * - `ScheduleRepository.currentWeek`（兜底推算）
 * - `ClassReminderScheduler.teachingWeekOf`（提醒排期）
 * - `ScheduleScreen.teachingWeekOf`（"下一节课"图钉）
 *
 * 现在统一走这里，调用方只表达"我要严格口径还是显示口径"。
 */
object WeekResolver {

    /** 一门课/一块时间所在的"周"来源：官方教学周日历优先，否则按开学日期逐周推算。 */
    private fun weekMondaysOf(semester: SettingsStore.SemesterConfig): List<String> = semester.weekMondays

    /**
     * **严格口径**：日期落在第几教学周；开学前、假期跳周、学期结束后都返回 null。
     *
     * 用于上课提醒排期与"下一节课"图钉——这些场景下开学前/假期本来就没有课，
     * 绝不能因为兜底推算而误排提醒。
     */
    fun teachingWeekOf(semester: SettingsStore.SemesterConfig, date: LocalDate): Int? =
        if (weekMondaysOf(semester).isNotEmpty()) {
            ScheduleRepository.teachingWeekOf(semester.weekMondays, date)
        } else {
            ScheduleRepository.currentWeek(semester.firstMonday, semester.totalWeeks, date)
        }

    /**
     * **显示口径**：今天在学期中的位置，供课表顶栏文案与默认定位使用。
     *
     * 与严格口径的差别：未开学时仍返回第 1 周（`beforeStart=true`），
     * 便于课表默认定位到第 1 周；状态文案据 [ScheduleRepository.WeekLocation.beforeStart]
     * 显示"未开学"而不是"第 1 周"。
     *
     * 两条分支必须给出**相同**的显示语义。官方校历分支在开学前返回 `week=1, beforeStart=true`
     * （见 ScheduleRepository.locateWeek），所以这里的兜底分支也必须把 `currentWeek` 的
     * null（严格口径：开学前无课）翻译回"显示为第 1 周"，否则无校历时顶栏会退化成
     * 既非"未开学"也非周次的空档。
     */
    /**
     * 用户**没有手动选周**时的默认落位（首屏、以及每次重新进入 App 重新定位时用）。
     *
     * - 教学周内 / 假期中：[ScheduleRepository.Companion.WeekLocation.week] 即目标
     *   （假期中它已经是假期后第一个教学周）；
     * - 已放假（`week == null` 且 `afterEnd`）：落到**最后一周**。放假期间翻课表不该
     *   每次重进都被拽回第 1 周——学期都结束了，"第 1 周"对使用者毫无意义；
     * - 其余（未开学、或还没设置学期）：第 1 周。
     */
    fun defaultWeek(
        location: ScheduleRepository.Companion.WeekLocation,
        totalWeeks: Int,
    ): Int = (location.week ?: if (location.afterEnd) totalWeeks else 1)
        .coerceIn(1, totalWeeks.coerceAtLeast(1))

    fun locateWeek(
        semester: SettingsStore.SemesterConfig,
        today: LocalDate = LocalDate.now(),
    ): ScheduleRepository.Companion.WeekLocation =
        if (weekMondaysOf(semester).isNotEmpty()) {
            ScheduleRepository.locateWeek(semester.weekMondays, today)
        } else {
            val strictWeek = ScheduleRepository.currentWeek(semester.firstMonday, semester.totalWeeks, today)
            val start = weekMonday(semester, 1)
            val beforeStart = start != null && today.isBefore(start)
            ScheduleRepository.Companion.WeekLocation(
                // 开学前按显示口径视为第 1 周
                week = if (beforeStart) 1 else strictWeek,
                isHoliday = false,
                nextWeekMonday = null,
                beforeStart = beforeStart,
                // 学期结束 = 已过最后一周的周日，且不属于"开学前"
                afterEnd = strictWeek == null && !beforeStart && start != null,
            )
        }

    /**
     * 第 [week] 教学周的周一日期（**周→周一映射的唯一实现**）。
     *
     * - 官方校历优先；
     * - 校历没覆盖到（用户把总周数调大、或本来就没有校历）时从最后一个已知周一顺延，
     *   没有校历时用**归一化到周一**的开学日期推算；
     * - 归一化必须与 [ScheduleRepository.currentWeek] 一致：那里用
     *   `previousOrSame(MONDAY)` 把非周一的开学日期归到那一周的周一，而日期行与
     *   `beforeStart` 此前直接用原始日期 → 开学日期是周三时，日期行整学期偏移 2 天、
     *   "今天"胶囊落在错误的列上，08-31/09-01 还会"未开学"与"第 1 周课程"同时出现。
     */
    fun weekMonday(semester: SettingsStore.SemesterConfig, week: Int): LocalDate? {
        if (week < 1) return null
        semester.weekMondays.getOrNull(week - 1)?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()?.let { return it }
        }
        val known = semester.weekMondays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (known.isNotEmpty()) {
            // 校历只到第 N 周：第 N + k 周 = 最后一个校历周一 + k 周
            val extra = week - known.size
            return known.last().plusWeeks(extra.toLong())
        }
        val start = runCatching { LocalDate.parse(semester.firstMonday) }.getOrNull() ?: return null
        val firstMonday = start.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return firstMonday.plusWeeks((week - 1).toLong())
    }
}
