package com.caeamer.beikeschedule.data.pref

/**
 * 已排到 AlarmManager 的一条提醒闹钟。
 *
 * requestCode 是唯一标识（用于精确取消），triggerAtMillis 是它的触发时刻。
 * 触发时刻必须一起记下来：重排时要靠它区分「还在未来的闹钟」（可以安全取消，稍后按新数据重排）
 * 与「已经到点、系统还没投递的闹钟」（绝不能取消 —— Doze 会把投递推到维护窗口，
 * 此时取消且旧代码又只重排"仍在未来"的，这条提醒就永久消失）。
 *
 * @param triggerAtMillis 旧版本只存了 requestCode，解析时该字段为 null。
 */
data class ScheduledAlarm(
    val requestCode: Int,
    val triggerAtMillis: Long?,
)
