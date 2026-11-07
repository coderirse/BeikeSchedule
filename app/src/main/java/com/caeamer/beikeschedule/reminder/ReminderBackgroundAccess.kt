package com.caeamer.beikeschedule.reminder

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** 标准后台权限可查询；厂商的自启动/后台耗电权限只能引导用户检查，不能假报已开启。 */
object ReminderBackgroundAccess {
    val isVivo: Boolean
        get() = Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("iqoo", ignoreCase = true)

    fun isBatteryOptimizationIgnored(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun isBackgroundRestricted(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun openBatterySettings(context: Context) = openFirst(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(context),
    )

    fun openVivoBackgroundSettings(context: Context) = openFirst(
        context,
        // OriginOS 的后台耗电列表受签名权限保护；应用详情中的「电量」可公开访问。
        appDetails(context),
    )

    fun openVivoStartupSettings(context: Context) = openFirst(
        context,
        Intent("com.iqoo.secure.BGSTARTUPMANAGER")
            .setPackage("com.vivo.permissionmanager"),
        appDetails(context),
    )

    private fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))

    private fun openFirst(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            ) return
        }
    }
}
