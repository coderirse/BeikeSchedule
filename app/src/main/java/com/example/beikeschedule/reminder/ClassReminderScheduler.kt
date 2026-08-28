package com.example.beikeschedule.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.data.repo.ScheduleRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 上课提醒调度：为未来 8 天内"当周有课"的每个课程块排一个闹钟提醒。
 * 全量重排策略 —— 课程/设置/学期任何变化时整体取消重排；
 * 已排闹钟的 requestCode 记录在 DataStore，删课/改设置也能精确取消。
 * 另挂一个每日脉冲闹钟兜底自续（应用长期不打开也能续期）。
 */
object ClassReminderScheduler {

    const val CHANNEL_ID = "class_reminder"
    const val ACTION_REMIND = "com.example.beikeschedule.action.REMIND"
    const val ACTION_DAILY_PULSE = "com.example.beikeschedule.action.DAILY_PULSE"
    const val EXTRA_NAME = "name"
    const val EXTRA_LOCATION = "location"
    const val EXTRA_TIME_TEXT = "timeText"
    const val EXTRA_MINUTES = "minutes"
    private const val REQUEST_DAILY_PULSE = 9_000_000
    private const val SCHEDULE_DAYS = 8

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "每节课开始前 N 分钟提醒" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 课程/学期/提醒设置变化时调用：取消旧闹钟，按最新数据重排。 */
    suspend fun reschedule(context: Context) {
        val repo = ScheduleRepository(context)
        cancelRecorded(context, repo.settings)

        if (repo.settings.reminderEnabled.first()) {
            val minutes = repo.settings.reminderMinutes.first()
            val semester = repo.settings.semester.first()
            val courses = repo.courses.first().filter { !it.isUnscheduled }
            val timeMap = repo.sectionTimes.first().associateBy { it.section }
            val now = LocalDateTime.now()
            val today = LocalDate.now()
            val scheduled = mutableSetOf<Int>()
            for (offset in 0 until SCHEDULE_DAYS) {
                val date = today.plusDays(offset.toLong())
                val week = teachingWeekOf(semester, date) ?: continue
                val dayOfWeek = date.dayOfWeek.value
                courses.forEach { course ->
                    if (course.dayOfWeek != dayOfWeek || !course.hasClassOnWeek(week)) return@forEach
                    val section = timeMap[course.startSection] ?: return@forEach
                    val startTime = runCatching { LocalTime.parse(section.startTime) }.getOrNull() ?: return@forEach
                    val trigger = LocalDateTime.of(date, startTime).minusMinutes(minutes.toLong())
                    if (trigger.isAfter(now)) {
                        val code = requestCodeOf(course, date)
                        setAlarm(context, remindPendingIntent(context, course.name, course.location, section.startTime, minutes, code), trigger)
                        scheduled += code
                    }
                }
            }
            repo.settings.saveReminderScheduledCodes(scheduled)
        }
        scheduleDailyPulse(context)
    }

    /** 日期落在第几教学周；假期跳周返回 null（假期无课）。 */
    private fun teachingWeekOf(semester: SettingsStore.SemesterConfig, date: LocalDate): Int? =
        if (semester.weekMondays.isNotEmpty()) {
            ScheduleRepository.locateWeek(semester.weekMondays, date)
                .takeIf { !it.isHoliday }?.week
        } else {
            ScheduleRepository.currentWeek(semester.firstMonday, semester.totalWeeks, date)
        }

    private fun requestCodeOf(course: CourseEntity, date: LocalDate): Int =
        "${course.id}@${date}".hashCode()

    private fun remindPendingIntent(
        context: Context,
        name: String,
        location: String,
        startTime: String,
        minutes: Int,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_LOCATION, location)
            .putExtra(EXTRA_TIME_TEXT, "$startTime 上课")
            .putExtra(EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setAlarm(context: Context, pending: PendingIntent, trigger: LocalDateTime) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Android 14+ 精确闹钟权限可能被系统收回，拿不到时退化为非精确（允许几分钟后延）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }

    /** 取消上次记录的全部课程提醒。 */
    private suspend fun cancelRecorded(context: Context, settings: SettingsStore) {
        val codes = settings.reminderScheduledCodes.first()
        if (codes.isEmpty()) return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        codes.forEach { code ->
            val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND)
            PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { alarmManager.cancel(it) }
        }
        settings.saveReminderScheduledCodes(emptySet())
    }

    /** 每日凌晨脉冲：触发一次 reschedule 让提醒窗口永远向前滚动。 */
    private fun scheduleDailyPulse(context: Context) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_DAILY_PULSE)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_DAILY_PULSE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextRun = LocalDate.now().plusDays(1).atTime(4, 30)
        val millis = nextRun.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }
}
