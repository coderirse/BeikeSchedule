package com.caeamer.beikeschedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import com.caeamer.beikeschedule.reminder.ReminderAlarmScheduler
import com.caeamer.beikeschedule.reminder.ReminderReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val code = 6_900_001

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ClassReminderScheduler.ACTION_REMIND)
            .putExtra(ClassReminderScheduler.EXTRA_NAME, "课前提醒测试")
            .putExtra(ClassReminderScheduler.EXTRA_LOCATION, "测试通知，可忽略")
            .putExtra(ClassReminderScheduler.EXTRA_TIME_TEXT, "后台投递验证")
            .putExtra(ClassReminderScheduler.EXTRA_MINUTES, 5)
            .putExtra(ReminderAlarmScheduler.EXTRA_REQUEST_CODE, code),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private suspend fun schedule(delayMillis: Long) {
        check(context.packageName.endsWith(".debug"))
        ReminderAlarmScheduler.apply(
            context, ClassReminderScheduler.ACTION_REMIND, emptyList(),
            listOf(ReminderAlarmScheduler.PlannedAlarm(code, System.currentTimeMillis() + delayMillis, pending())),
            persist = {},
        )
    }

    @Test(timeout = 35_000)
    fun scheduledAlarmPostsClassNotification() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(code)
        try {
            schedule(8_000)
            val deadline = SystemClock.elapsedRealtime() + 25_000
            while (manager.activeNotifications.none { it.id == code } && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(250)
            }
            val notification = manager.activeNotifications.firstOrNull { it.id == code }
            assertTrue("系统闹钟必须触发真正的课前通知", notification != null)
            assertEquals("5 分钟后上课：课前提醒测试", notification!!.notification.extras.getString("android.title"))
        } finally {
            context.getSystemService(AlarmManager::class.java).cancel(pending())
            manager.cancel(code)
        }
    }

}
