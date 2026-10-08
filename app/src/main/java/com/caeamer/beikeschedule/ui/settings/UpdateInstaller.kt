package com.caeamer.beikeschedule.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 应用内更新下载：OkHttp 流式拉直链 APK（带字节级进度）→ **SHA-256 校验** → 交系统安装器。
 *
 * 为什么必须有校验这一步：自有源在 ICP 备案前是明文 HTTP，元数据虽经 Ed25519 验签，
 * 但 MITM 可在 APK 下载阶段替换文件——Android 的同签名检查只能让安装失败，
 * 用户看到的是不可诊断的报错。下载完成后先算摘要与签名里认证过的 `apkSha256`
 * 比对，一致才交给安装器（R4 审查 R5）。
 *
 * 为什么是 OkHttp 而不是 DownloadManager：界面要字节级进度条（DownloadManager 只能轮询
 * 查询，且部分系统镜像上干脆不落盘——R5 发版实测的悬案，这里直接绕开）；应用的网络栈
 * 本就是 OkHttp（见 CloudApi）。下载进度经 [progress] 暴露给「我的」页的更新卡片。
 *
 * 弱网健壮性：自有源走 Cloudflare Tunnel，长连接可能整段停滞（模拟器实测 385KB 后卡死，
 * 超过 30s 读超时）。因此写入 `.part` 临时文件、失败后按 `Range` 头**断点续传**最多
 * [MAX_ATTEMPTS] 次；服务端支持 Accept-Ranges，返回 200（忽略 Range）则从头重来。
 * 摘要校验兜住续传拼接的正确性——拼错的文件过不了 sha256 这关。
 *
 * 用法约束：`url` 必须是直链 APK 且 `sha256Hex` 非空（调用方见 ProfileScreen）；
 * GitHub 兜底是 HTML 页面，仍走浏览器打开。
 */
object UpdateInstaller {

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val DOWNLOAD_NAME = "beikeschedule-update.apk"
    private const val TMP_SUFFIX = ".part"

    /** 进度节流步长：每 256KB 发一次（收敛重组频率，10MB 级 APK 也就 ~40 次）。 */
    private const val PROGRESS_STEP_BYTES = 256L * 1024

    /** 自动重试上限（含首次；续传基于 .part 已有字节）。 */
    private const val MAX_ATTEMPTS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 下载进度：total <= 0 表示服务端未给 Content-Length（界面显示不定长进度条）。 */
    data class Progress(val bytes: Long, val total: Long)

    private val _progress = MutableStateFlow<Progress?>(null)

    /** 当前下载进度；null = 没有下载在进行（界面据此在按钮与进度条间切换）。 */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** 在跑的下载协程：连点去重用。 */
    private var activeJob: Job? = null

    /** 是否可走应用内下载链路：直链 APK 且有已认证的摘要。 */
    fun canInstallInApp(url: String, sha256Hex: String): Boolean =
        sha256Hex.isNotBlank() && url.substringBefore('?').lowercase().endsWith(".apk")

    /**
     * 发起下载并在完成后校验摘要、弹系统安装器。失败（网络/摘要不符）只 Toast + 清残留，
     * 不打断用户（更新卡片/强更弹窗还在，可重试；.part 保留供续传）。
     */
    fun downloadAndInstall(context: Context, url: String, sha256Hex: String) {
        val app = context.applicationContext
        // 连点去重：并发两次下载会带来两次摘要校验与两次拉起安装器
        if (activeJob?.isActive == true) {
            toast(app, "更新包正在下载中")
            return
        }
        activeJob = scope.launch { runDownload(app, url, sha256Hex) }
    }

    private fun apkFile(app: Context): File =
        File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_NAME)

    private suspend fun runDownload(app: Context, url: String, sha256Hex: String) {
        val file = apkFile(app)
        val tmp = File(file.parentFile, DOWNLOAD_NAME + TMP_SUFFIX)
        // 清掉上一次更新留下的成品残留（成功安装后不能立刻删：安装器异步读文件，
        // 残留只能留到下一次更新收拾——这里就是"下一次"）。.part 不删：它是续传的起点。
        runCatching { file.delete() }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 读超时只约束"一个 read 无数据的最长时间"：慢速但仍在动的流不会误杀
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        try {
            for (attempt in 1..MAX_ATTEMPTS) {
                val downloaded = if (tmp.exists()) tmp.length() else 0L
                _progress.value = Progress(downloaded, 0)
                try {
                    val request = Request.Builder().url(url).apply {
                        if (downloaded > 0) header("Range", "bytes=$downloaded-")
                    }.build()
                    client.newCall(request).execute().use { resp ->
                        when (resp.code) {
                            200, 206 -> Unit
                            // 416 = 已下载字节数超过文件实际长度（服务端换包/续传错位）：
                            // 丢弃旧 .part 从头来
                            416 -> {
                                runCatching { tmp.delete() }
                                throw IOException("续传错位（HTTP 416）")
                            }
                            else -> {
                                _progress.value = null
                                toastMain(app, "下载失败（HTTP ${resp.code}），请重试")
                                return
                            }
                        }
                        val body = resp.body ?: run {
                            _progress.value = null
                            toastMain(app, "下载失败：响应为空，请重试")
                            return
                        }
                        // 206 的 Content-Length 只是剩余部分；进度按"已有 + 本次"计
                        val startBytes = if (resp.code == 206) {
                            downloaded
                        } else {
                            runCatching { tmp.delete() }
                            0L
                        }
                        val total = body.contentLength().takeIf { it > 0 }?.let { startBytes + it } ?: -1L
                        appendStream(body.byteStream(), tmp, startBytes, total)
                    }
                    break // 本次 attempt 成功，跳出重试循环
                } catch (e: IOException) {
                    if (attempt == MAX_ATTEMPTS) {
                        // .part 故意保留：用户再点「获取更新」时从断点继续
                        _progress.value = null
                        toastMain(app, "下载失败（${e.message ?: "网络异常"}），已保留进度，请重试")
                        return
                    }
                    delay(1_000) // 小退避后续传重试
                }
            }

            _progress.value = Progress(tmp.length(), tmp.length())
            // 摘要不符 = 下载被篡改或服务端包与签名不一致（含续传拼接出错），宁可拒绝安装
            val actual = withContext(Dispatchers.IO) { sha256HexOf(tmp) }
            if (!actual.equals(sha256Hex, ignoreCase = true)) {
                runCatching { tmp.delete() }
                _progress.value = null
                toastMain(app, "更新包校验失败，已取消安装（请稍后重试或到 GitHub 下载）")
                return
            }
            // 成功路径不删文件：系统安装器还要读。先改名成成品，再交给系统包安装确认页。
            if (!tmp.renameTo(file)) {
                runCatching { tmp.delete() }
                _progress.value = null
                toastMain(app, "下载文件落盘失败，请重试")
                return
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            _progress.value = null
            runCatching { app.startActivity(install) }.onFailure {
                toastMain(app, "无法启动安装，请确认已允许本应用安装应用")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { file.delete() }
            _progress.value = null
            toastMain(app, "下载失败（${e.message ?: "网络异常"}），请重试")
        }
    }

    /** 把响应流追加写入 [tmp]，每 [PROGRESS_STEP_BYTES] 与收尾时回报一次进度。 */
    private suspend fun appendStream(input: InputStream, tmp: File, startBytes: Long, total: Long) =
        withContext(Dispatchers.IO) {
            var written = 0L
            var lastEmit = 0L
            java.io.FileOutputStream(tmp, /* append = */ true).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (written - lastEmit >= PROGRESS_STEP_BYTES) {
                        _progress.value = Progress(startBytes + written, total)
                        lastEmit = written
                    }
                }
            }
            _progress.value = Progress(startBytes + written, total)
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
