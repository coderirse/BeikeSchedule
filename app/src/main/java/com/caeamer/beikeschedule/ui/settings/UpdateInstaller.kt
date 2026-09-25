package com.caeamer.beikeschedule.ui.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用内更新下载：DownloadManager 拉直链 APK → **SHA-256 校验** → 交系统安装器。
 *
 * 为什么必须有这一步：自有源在 ICP 备案前是明文 HTTP，元数据虽经 Ed25519 验签，
 * 但 MITM 可在 APK 下载阶段替换文件——Android 的同签名检查只能让安装失败，
 * 用户看到的是不可诊断的报错。下载完成后先算摘要与签名里认证过的 `apkSha256`
 * 比对，一致才交给安装器（R4 审查 R5）。
 *
 * 用法约束：`url` 必须是直链 APK 且 `sha256Hex` 非空（调用方见 ProfileScreen）；
 * GitHub 兜底是 HTML 页面，仍走浏览器打开。
 */
object UpdateInstaller {

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val DOWNLOAD_NAME = "beikeschedule-update.apk"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否可走应用内下载链路：直链 APK 且有已认证的摘要。 */
    fun canInstallInApp(url: String, sha256Hex: String): Boolean =
        sha256Hex.isNotBlank() && url.substringBefore('?').lowercase().endsWith(".apk")

    /**
     * 发起下载并在完成后校验摘要、弹安装器。失败（网络/摘要不符）只 Toast + 清残留，
     * 不打断用户（force 弹窗还在，可重试）。
     */
    fun downloadAndInstall(context: Context, url: String, sha256Hex: String) {
        val app = context.applicationContext
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: run {
            toast(app, "下载服务不可用，请改用浏览器下载")
            return
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("贝壳课表更新包")
            .setDescription("下载完成后自动校验并安装")
            .setMimeType(MIME_APK)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, DOWNLOAD_NAME)
            .setAllowedOverMetered(true)
        val id = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                app.unregisterReceiver(this)
                scope.launch { finishAndInstall(app, dm, id, sha256Hex) }
            }
        }
        // ACTION_DOWNLOAD_COMPLETE 是 DownloadManager 定向发给本应用的受保护广播
        app.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    private suspend fun finishAndInstall(app: Context, dm: DownloadManager, id: Long, sha256Hex: String) {
        val query = DownloadManager.Query().setFilterById(id)
        dm.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                dm.remove(id)
                toastMain(app, "下载未完成，请重试")
                return
            }
        }
        val file = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_NAME)
        if (!file.exists()) {
            toastMain(app, "下载文件缺失，请重试")
            return
        }
        val actual = withContext(Dispatchers.IO) { sha256HexOf(file) }
        if (!actual.equals(sha256Hex, ignoreCase = true)) {
            file.delete()
            // 摘要不符 = 下载被篡改或服务端包与签名不一致，宁可拒绝安装
            toastMain(app, "更新包校验失败，已取消安装（请稍后重试或到 GitHub 下载）")
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(install) }.onFailure {
            toastMain(app, "无法启动安装，请确认已允许本应用安装应用")
        }
    }

    private fun sha256HexOf(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private suspend fun toastMain(context: Context, message: String) {
        withContext(Dispatchers.Main) { toast(context, message) }
    }
}
