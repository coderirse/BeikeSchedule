package com.caeamer.beikeschedule.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.caeamer.beikeschedule.MainActivity
import com.caeamer.beikeschedule.R
import com.caeamer.beikeschedule.model.CourseMerger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 课程提醒、考试提醒与每日脉冲的接收器。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ClassReminderScheduler.ACTION_REMIND -> {
                // 只弹通知，绝不在此重排：reschedule 内部会先取消全部闹钟再重排"仍在未来"的，
                // 若在提醒触发时重排，此刻已到点但尚未被系统投递的闹钟（Doze 延迟、同时间多节课）
                // 会先被取消又不再重排，导致提醒永久丢失——这正是"时好时坏"的主因。
                // 8 天排期窗口的前移由每日脉冲（ACTION_DAILY_PULSE）与开机/打开 App 时的重排负责。
                showClassNotification(context, intent)
            }
            ExamReminderScheduler.ACTION_EXAM_REMIND -> showExamNotification(context, intent)
            ClassReminderScheduler.ACTION_DAILY_PULSE -> rescheduleAsync(context)
        }
    }

    private fun rescheduleAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 根协程未捕获异常会直接崩进程，这里只允许失败为"本轮不重排"
                runCatching {
                    ClassReminderScheduler.reschedule(context.applicationContext)
                    ExamReminderScheduler.reschedule(context.applicationContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun showClassNotification(context: Context, intent: Intent) {
        if (notificationPermissionDenied(context)) return

        ClassReminderScheduler.ensureChannel(context)
        val name = intent.getStringExtra(ClassReminderScheduler.EXTRA_NAME).orEmpty()
        // 剥【校区】前缀并与 CourseMerger 共用同一规则；占位地点 "-"（含 "【校本部】-"）
        // 一律当作"无地点"，否则通知里会出现一个裸 "-"。
        val location = CourseMerger.stripCampusPrefix(
            intent.getStringExtra(ClassReminderScheduler.EXTRA_LOCATION).orEmpty(),
        ).takeIf { it.isNotEmpty() && it != "-" }.orEmpty()
        val timeText = intent.getStringExtra(ClassReminderScheduler.EXTRA_TIME_TEXT).orEmpty()
        val minutes = intent.getIntExtra(ClassReminderScheduler.EXTRA_MINUTES, 15)

        val notification = NotificationCompat.Builder(context, ClassReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$minutes 分钟后上课：$name")
            .setContentText(listOf(timeText, location).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(intent, fallbackSeed = name), notification)
    }

    private fun showExamNotification(context: Context, intent: Intent) {
        if (notificationPermissionDenied(context)) return

        ExamReminderScheduler.ensureChannel(context)
        val name = intent.getStringExtra(ExamReminderScheduler.EXTRA_NAME).orEmpty()
        val timeText = intent.getStringExtra(ExamReminderScheduler.EXTRA_TIME_TEXT).orEmpty()
        val location = intent.getStringExtra(ExamReminderScheduler.EXTRA_LOCATION).orEmpty()
        val seat = intent.getStringExtra(ExamReminderScheduler.EXTRA_SEAT).orEmpty()
        val titlePrefix = intent.getStringExtra(ExamReminderScheduler.EXTRA_TITLE_PREFIX).orEmpty()

        val details = listOf(timeText, location, if (seat.isNotBlank()) "座位 $seat" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")

        val notification = NotificationCompat.Builder(context, ExamReminderScheduler.EXAM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$titlePrefix：$name")
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(intent, fallbackSeed = name), notification)
    }

    /**
     * 通知 ID = 该闹钟的 requestCode。
     *
     * 旧实现用 `System.currentTimeMillis() % Int.MAX_VALUE`：同一天同一时间的两门课（真冲突）
     * 闹钟会同批投递，若落在同一毫秒就得到同一个 ID，后一条把前一条覆盖掉 —— 表现为"只收到一条"。
     * requestCode 由 (课程, 日期) 内容寻址，稳定且唯一，天然不会撞车
     * （上课提醒在 [0, 8e6)，考试提醒在 8e6 段，两类的通知 ID 也不会互相覆盖）。
     */
    private fun notificationId(intent: Intent, fallbackSeed: String): Int {
        val code = intent.getIntExtra(ReminderAlarmScheduler.EXTRA_REQUEST_CODE, Int.MIN_VALUE)
        return if (code != Int.MIN_VALUE) code else Math.floorMod(fallbackSeed.hashCode(), 1_000_000)
    }

    private fun notificationPermissionDenied(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
