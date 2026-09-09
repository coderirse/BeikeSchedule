package com.caeamer.beikeschedule.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.model.ReminderCourses
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    const val ACTION_REMIND = "com.caeamer.beikeschedule.action.REMIND"
    const val ACTION_DAILY_PULSE = "com.caeamer.beikeschedule.action.DAILY_PULSE"
    const val EXTRA_NAME = "name"
    const val EXTRA_LOCATION = "location"
    const val EXTRA_TIME_TEXT = "timeText"
    const val EXTRA_MINUTES = "minutes"
    private const val REQUEST_DAILY_PULSE = 9_000_000
    private const val SCHEDULE_DAYS = 8

    /**
     * 重排串行化：reschedule 会被 App 打开、每日脉冲、开机广播等多处并发触发，
     * 而它内部是"取消全部 → 重新排"的非原子序列，交错执行会让后一次的取消吃掉
     * 前一次刚排好的闹钟（表现为偶发丢提醒）。
     */
    private val rescheduleMutex = Mutex()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "上课提醒", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "每节课开始前 N 分钟提醒" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 需要排提醒的课程：排除无固定时间与已隐藏课程，并合并教务拆出的同名同段多行
     * （否则同一节课会因多行各排一个闹钟而重复弹两次）。
     */
    internal fun reminderCourses(all: List<CourseEntity>): List<CourseEntity> =
        ReminderCourses.eligible(all)

    /** 课程/学期/提醒设置变化时调用：取消旧闹钟，按最新数据重排。 */
    suspend fun reschedule(context: Context) = rescheduleMutex.withLock {
        val repo = ScheduleRepository(context)
        cancelRecorded(context, repo.settings)

        if (repo.settings.reminderEnabled.first()) {
            val minutes = repo.settings.reminderMinutes.first()
            val semester = repo.settings.semester.first()
            val courses = reminderCourses(repo.courses.first())
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

    /** 日期落在第几教学周；开学前/假期跳周/学期外都返回 null（那些天本来就没课）。 */
    private fun teachingWeekOf(semester: SettingsStore.SemesterConfig, date: LocalDate): Int? =
        if (semester.weekMondays.isNotEmpty()) {
            ScheduleRepository.teachingWeekOf(semester.weekMondays, date)
        } else {
            ScheduleRepository.currentWeek(semester.firstMonday, semester.totalWeeks, date)
        }

    // 32 位 hash 理论上可碰撞（碰撞只会覆盖一个闹钟），窗口内 <100 个闹钟概率约 1e-6，可接受
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

    /**
     * 每日凌晨脉冲：触发一次 reschedule 让 8 天排期窗口永远向前滚动。
     *
     * 用 setInexactRepeating 而非"一次性闹钟 + 触发后重新排自己"：
     * 一次性脉冲一旦某次没送达（设备关机/Doze 深睡/OEM 清理）就永久不再续期，
     * 8 天后所有提醒静默失效；重复闹钟由系统常驻，不依赖 App 每次重新武装。
     * 续期只需在凌晨大致跑一次，非精确即可，也无需精确闹钟权限。
     */
    private fun scheduleDailyPulse(context: Context) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_DAILY_PULSE)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_DAILY_PULSE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextRun = LocalDate.now().plusDays(1).atTime(4, 30)
        val millis = nextRun.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java)
            .setInexactRepeating(AlarmManager.RTC_WAKEUP, millis, AlarmManager.INTERVAL_DAY, pending)
    }
}
