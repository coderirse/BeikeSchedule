package com.caeamer.beikeschedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 开机后重排课程与考试提醒（闹钟不随重启保留）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
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
}
