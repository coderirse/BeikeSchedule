package com.caeamer.beikeschedule.data.pref

/**
 * 已排闹钟记录与 DataStore 字符串之间的编解码。
 *
 * 格式：`"code:triggerMillis,code:triggerMillis,…"`。
 * 旧版本只存了 requestCode（`"code,code,…"`），解析时把触发时刻读成 null ——
 * 上层据此走"仍在本轮计划里就先留着"的保守分支，不会因为升级而丢掉已到点未投递的提醒。
 */
internal object AlarmCodec {

    fun encode(alarms: List<ScheduledAlarm>): String =
        alarms.joinToString(",") { a ->
            if (a.triggerAtMillis == null) "${a.requestCode}" else "${a.requestCode}:${a.triggerAtMillis}"
        }

    fun decode(raw: String?): List<ScheduledAlarm> =
        raw?.takeIf { it.isNotBlank() }?.split(",")?.mapNotNull { part ->
            val code = part.substringBefore(":").trim().toIntOrNull() ?: return@mapNotNull null
            ScheduledAlarm(code, part.substringAfter(":", "").trim().toLongOrNull())
        } ?: emptyList()
}
