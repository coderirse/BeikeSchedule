package com.caeamer.beikeschedule.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.caeamer.beikeschedule.MainActivity
import com.caeamer.beikeschedule.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 课程提醒与每日脉冲的接收器。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ClassReminderScheduler.ACTION_REMIND -> {
                showNotification(context, intent)
                // 触发后窗口前移一天，顺带自续脉冲
                rescheduleAsync(context)
            }
            ClassReminderScheduler.ACTION_DAILY_PULSE -> rescheduleAsync(context)
        }
    }

    private fun rescheduleAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 根协程未捕获异常会直接崩进程，这里只允许失败为"本轮不重排"
                runCatching { ClassReminderScheduler.reschedule(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun showNotification(context: Context, intent: Intent) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        ClassReminderScheduler.ensureChannel(context)
        val name = intent.getStringExtra(ClassReminderScheduler.EXTRA_NAME).orEmpty()
        val location = intent.getStringExtra(ClassReminderScheduler.EXTRA_LOCATION)
            .orEmpty().replace(Regex("【[^】]*】"), "")
        val timeText = intent.getStringExtra(ClassReminderScheduler.EXTRA_TIME_TEXT).orEmpty()
        val minutes = intent.getIntExtra(ClassReminderScheduler.EXTRA_MINUTES, 15)

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ClassReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$minutes 分钟后上课：$name")
            .setContentText(listOf(timeText, location).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
