package com.caeamer.beikeschedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机、"时间口径变化"与**应用被覆盖安装**后重排课程/考试/日程提醒。
 *
 * 闹钟不随重启保留，所以要监听 BOOT_COMPLETED；而所有闹钟都按 epoch 毫秒排、
 * 计划却按本地时间算，跨时区或手动改时间后已排的闹钟会整体错点，
 * 所以 TIME_SET / TIMEZONE_CHANGED / DATE_CHANGED 也要走一遍同样的全量重排
 * （reschedule 是幂等的"先算后换"，多跑一次没有副作用）。
 *
 * MY_PACKAGE_REPLACED 同理但更关键：系统在本应用被更新后清空该包**全部**闹钟（含每日
 * 脉冲），而考试提醒没有"打开 App 即重排"的路径，不监听它就只能等用户下次开机。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val app = context.applicationContext
            try {
                // 根协程未捕获异常会直接崩进程，所以每个都要 runCatching；但**不能**共用一个
                // 包住三个——第一个抛异常（DataStore/Room IO 错误，SettingsStore 注释里自认
                // 可能发生）会连带跳过考试与日程的重排。
                runCatching { ClassReminderScheduler.reschedule(app) }
                runCatching { ExamReminderScheduler.reschedule(app) }
                runCatching { TodoReminderScheduler.reschedule(app) }
            } finally {
                pending.finish()
            }
        }
    }
}
