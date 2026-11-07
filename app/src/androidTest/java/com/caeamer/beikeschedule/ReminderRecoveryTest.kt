package com.caeamer.beikeschedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.WeekResolver
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import com.caeamer.beikeschedule.reminder.ReminderReceiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@RunWith(AndroidJUnit4::class)
class ReminderRecoveryTest {
    /** 模拟 OEM 删除闹钟，设置/课程均不变化，仅后台返回必须恢复系统中的闹钟。 */
    @Test(timeout = 60_000)
    fun resumeRestoresRemovedAlarmWithoutChangingSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 只在独立调试包运行，绝不改正式包内的真实课表。
        check(context.packageName.endsWith(".debug"))
        val db = AppDatabase.get(context)
        val settings = SettingsStore(context)
        val originalSemester = settings.semester.first()
        val originalEnabled = settings.reminderEnabled.first()
        val originalMinutes = settings.reminderMinutes.first()
        val originalSections = db.sectionTimeDao().observeAll().first()
        val semester = SettingsStore.SemesterConfig(
            firstMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),
            totalWeeks = 3,
        )
        val date = (1L..7L).map { LocalDate.now().plusDays(it) }
            .first { !WeekResolver.daySchedule(semester, it).holiday }
        val course = CourseEntity(
            id = 9_900_001, taskId = "", name = "提醒恢复回归测试", teacher = "", location = "",
            dayOfWeek = WeekResolver.daySchedule(semester, date).coursesDayOfWeek,
            startSection = 99, endSection = 99, weekBitmap = "0111", colorIndex = 0,
            source = CourseEntity.SOURCE_MANUAL,
        )
        val launchCommand = "am start -W --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${context.packageName}/${MainActivity::class.java.name}"
        try {
            db.courseDao().insertAll(listOf(course))
            db.sectionTimeDao().insertAll(listOf(SectionTimeEntity(99, "12:00", "12:45")))
            settings.saveSemester(semester)
            settings.setReminder(true, 15)
            // OriginOS 会拦截 Instrumentation.startActivitySync 的后台启动，用 shell 模拟用户点击。
            assertLaunch(shell(launchCommand))
            val code = ClassReminderScheduler.requestCodeOf(course, date)
            val trigger = LocalDateTime.of(date, LocalTime.NOON).minusMinutes(15)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            fun pending() = PendingIntent.getBroadcast(
                context, code,
                Intent(context, ReminderReceiver::class.java).setAction(ClassReminderScheduler.ACTION_REMIND),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            await { isAppInStage(Stage.RESUMED) && pending() != null && hasActiveAlarm(trigger) }
            shell("input keyevent KEYCODE_HOME")
            // Home 是异步切换，必须等到 Activity 真正离开前台，否则第二次启动不会触发 onResume。
            await { isAppInStage(Stage.STOPPED) }
            context.getSystemService(AlarmManager::class.java).cancel(checkNotNull(pending()))
            assertFalse("系统中的闹钟已被移除，但持久化排期仍然存在", hasActiveAlarm(trigger))
            assertTrue(settings.reminderScheduledAlarms.first().any { it.requestCode == code })
            assertLaunch(shell(launchCommand))
            await { isAppInStage(Stage.RESUMED) && hasActiveAlarm(trigger) }
        } finally {
            shell("input keyevent KEYCODE_HOME")
            db.courseDao().deleteById(course.id)
            db.sectionTimeDao().clear()
            db.sectionTimeDao().insertAll(originalSections)
            settings.saveSemester(originalSemester)
            settings.setReminder(originalEnabled, originalMinutes)
            ClassReminderScheduler.reschedule(context)
        }
    }

    private fun hasActiveAlarm(trigger: Long): Boolean {
        val dump = shell("dumpsys alarm")
        // 历史移除记录也会含 action，只匹配活动闹钟的 origWhen。
        return dump.lineSequence().any {
            it.contains("origWhen $trigger ") && it.contains(ClassReminderScheduler.ACTION_REMIND)
        }
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun assertLaunch(result: String) {
        android.util.Log.i("ReminderRecoveryTest", result)
        assertTrue("无法启动应用: $result", result.contains("Status: ok"))
    }

    private fun isAppInStage(stage: Stage): Boolean {
        var resumed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(stage).any { it is MainActivity }
        }
        return resumed
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(200)
        }
        assertTrue("等待系统闹钟恢复超时", condition())
    }
}
