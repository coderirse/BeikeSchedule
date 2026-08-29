package com.caeamer.beikeschedule.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 考前提醒调度：为未来 30 天内、能解析出日期的每场考试排两个闹钟——
 * 考前一天 20:00 与开考前 1 小时（无开始时间则只排前者）。
 * 全量重排策略与课程提醒一致；requestCode 固定使用 8_000_000 段，与其他闹钟隔离。
 */
object ExamReminderScheduler {

    const val EXAM_CHANNEL_ID = "exam_reminder"
    const val ACTION_EXAM_REMIND = "com.caeamer.beikeschedule.action.EXAM_REMIND"
    const val EXTRA_NAME = "name"
    const val EXTRA_TIME_TEXT = "timeText"
    const val EXTRA_LOCATION = "location"
    const val EXTRA_SEAT = "seat"
    const val EXTRA_TITLE_PREFIX = "titlePrefix"
    private const val REQUEST_CODE_BASE = 8_000_000
    private const val SCHEDULE_DAYS = 30

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            EXAM_CHANNEL_ID, "考试提醒", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "考试前一天与开考前提醒" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 考试数据变化/开机/每日脉冲时调用：取消旧闹钟，按最新数据全量重排。 */
    suspend fun reschedule(context: Context) {
        val repo = ScheduleRepository(context)
        cancelRecorded(context, repo)
        val exams = repo.exams.first()
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        val scheduled = mutableSetOf<Int>()
        for (exam in exams) {
            if (!exam.hasDate) continue
            val date = runCatching { LocalDate.parse(exam.ksrq) }.getOrNull() ?: continue
            if (date.isBefore(today) || date.isAfter(today.plusDays(SCHEDULE_DAYS.toLong()))) continue

            // 考前一天 20:00
            val dayBefore = LocalDateTime.of(date.minusDays(1), LocalTime.of(20, 0))
            if (dayBefore.isAfter(now)) {
                val code = requestCodeOf(exam, true)
                setAlarm(context, pendingIntent(context, exam, "明天考试", code), dayBefore)
                scheduled += code
            }
            // 开考前 1 小时（需要解析出开始时间）
            val start = runCatching { LocalTime.parse(exam.kssj) }.getOrNull()
            if (start != null) {
                val oneHourBefore = LocalDateTime.of(date, start).minusHours(1)
                if (oneHourBefore.isAfter(now)) {
                    val code = requestCodeOf(exam, false)
                    setAlarm(context, pendingIntent(context, exam, "即将考试", code), oneHourBefore)
                    scheduled += code
                }
            }
        }
        repo.settings.saveExamReminderScheduledCodes(scheduled)
    }

    // 8_000_000 段：examId*2(+1)，与其他闹钟 requestCode 空间隔离
    private fun requestCodeOf(exam: com.caeamer.beikeschedule.data.local.ExamEntity, dayBefore: Boolean): Int =
        REQUEST_CODE_BASE + (exam.id * 2).toInt() + if (dayBefore) 0 else 1

    private fun examTimeText(exam: com.caeamer.beikeschedule.data.local.ExamEntity): String = when {
        exam.kssj.isNotBlank() && exam.jssj.isNotBlank() -> "${exam.ksrq} ${exam.kssj}-${exam.jssj}"
        exam.ksrq.isNotBlank() -> exam.ksrq
        else -> exam.kssjms
    }

    private fun pendingIntent(
        context: Context,
        exam: com.caeamer.beikeschedule.data.local.ExamEntity,
        titlePrefix: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_EXAM_REMIND)
            .putExtra(EXTRA_NAME, exam.kcmc)
            .putExtra(EXTRA_TIME_TEXT, examTimeText(exam))
            .putExtra(EXTRA_LOCATION, exam.cdmc)
            .putExtra(EXTRA_SEAT, exam.zwh)
            .putExtra(EXTRA_TITLE_PREFIX, titlePrefix)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setAlarm(context: Context, pending: PendingIntent, trigger: LocalDateTime) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val millis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }

    private suspend fun cancelRecorded(context: Context, repo: ScheduleRepository) {
        val codes = repo.settings.examReminderScheduledCodes.first()
        if (codes.isEmpty()) return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        codes.forEach { code ->
            val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_EXAM_REMIND)
            PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { alarmManager.cancel(it) }
        }
        repo.settings.saveExamReminderScheduledCodes(emptySet())
    }
}
