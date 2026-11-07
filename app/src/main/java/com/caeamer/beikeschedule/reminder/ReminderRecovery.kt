package com.caeamer.beikeschedule.reminder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 系统可能在后台移除闹钟，DataStore 中的排期记录不能用来判断是否需要恢复。 */
object ReminderRecovery {
    private val mutex = Mutex()

    suspend fun rescheduleAll(context: Context) = mutex.withLock {
        val app = context.applicationContext
        // 一类失败不能阻止另外两类恢复；取消仍交给调用方的生命周期处理。
        recover("class") { ClassReminderScheduler.reschedule(app) }
        recover("exam") { ExamReminderScheduler.reschedule(app) }
        recover("todo") { TodoReminderScheduler.reschedule(app) }
    }

    private suspend fun recover(kind: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ReminderRecovery", "Failed to restore $kind reminders", e)
        }
    }
}
