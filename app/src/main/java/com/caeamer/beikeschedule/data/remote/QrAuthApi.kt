package com.caeamer.beikeschedule.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 贝壳教学平台校园微认证（sis.ustb.edu.cn）的二维码授权。
 *
 * **为什么由 App 自己轮询**：SSO 页面里的二维码 iframe 自带 30 秒超时的轮询，
 * 在慢网络上请求会超时被 abort → 页面代码"失败即 reload" → 每次 reload 生成新 sid、
 * 旧二维码作废——同机扫码（切去微信再回来）几乎必失败。这里改为：
 * WebView 只负责展示二维码；App 通过 shouldInterceptRequest 原生捕获 sid，
 * 自己长轮询 /connect/state，拿到授权码后把主框架导航到 SSO 回调地址。
 * 页面自身怎么刷新都不影响这条链路。
 */
object QrAuthApi {

    private const val BASE = "https://sis.ustb.edu.cn"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 服务端长轮询最多挂 ~20 秒，读超时留足
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 一次二维码会话的参数（sid 来自 qrimg 请求，其余来自 qrpage 请求）。 */
    data class QrTarget(
        val sid: String,
        val appId: String,
        val randToken: String,
        val retUrl: String,
    )

    @Serializable
    private data class StateResponse(
        val code: Int = -1,
        val message: String = "",
        // 服务端可能是纯字符串，也可能是 {authCode/auth_code/...} 对象；用 JsonElement 兼容
        val data: JsonElement? = null,
        val authCode: String? = null,
        val auth_code: String? = null,
    )

    data class QrState(val code: Int, val authCode: String? = null)

    /** 长轮询一次二维码状态（code=1 时 [QrState.authCode] 有效）。 */
    suspend fun pollState(sid: String): QrState = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(pollStateUrl(sid)).get().build()
        val response = runCatching { client.newCall(request).execute() }.getOrElse {
            throw IOException("联网失败", it)
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching {
                json.decodeFromString(StateResponse.serializer(), text)
            }.getOrElse {
                throw IOException("状态响应格式异常")
            }
            QrState(
                code = parsed.code,
                authCode = extractAuthCode(parsed.authCode, parsed.auth_code, parsed.data),
            )
        }
    }

    /**
     * 从状态响应里取授权码：兼容字符串 / `data` 为字符串 / `{authCode|auth_code|code}` 对象。
     * code=1 但取不到时返回 null（调用方应继续轮询，不能直接放弃）。
     */
    internal fun extractAuthCode(vararg candidates: Any?): String? {
        for (c in candidates) {
            when (c) {
                null -> Unit
                is String -> c.trim().takeIf { it.isNotEmpty() }?.let { return it }
                is JsonNull -> Unit
                is JsonPrimitive -> c.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                is JsonObject -> {
                    for (key in AUTH_CODE_KEYS) {
                        val nested = c[key] ?: continue
                        extractAuthCode(nested)?.let { return it }
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private val AUTH_CODE_KEYS = listOf("authCode", "auth_code", "authcode", "code", "token", "data")

    /** `/connect/state` 地址：sid 进 query，特殊字符由 [HttpUrl] 编码。 */
    internal fun pollStateUrl(sid: String): String =
        "$BASE/connect/state".toHttpUrl().newBuilder()
            .addQueryParameter("sid", sid)
            .build()
            .toString()

    /**
     * 授权成功后要导航的 SSO 回调地址：与二维码页面自身 code==1 分支拼法一致
     * （retUrl + appid + auth_code + rand_token），SSO 校验后种下会话并跳回教务系统。
     *
     * 参数值统一 URL 编码：`auth_code` 来自服务端响应，含 `&`/`=` 时直接拼接会拆断查询串。
     */
    fun authorizeUrl(target: QrTarget, authCode: String): String {
        val sep = when {
            !target.retUrl.contains("?") -> "?"
            target.retUrl.endsWith("?") -> ""
            else -> "&"
        }
        return target.retUrl + sep +
            "appid=" + encodeQuery(target.appId) +
            "&auth_code=" + encodeQuery(authCode) +
            "&rand_token=" + encodeQuery(target.randToken)
    }

    /** 同机扫码的替代路径：在微信中打开该链接即可直接进入授权确认页。 */
    fun wechatAuthorizeUrl(sid: String): String =
        "$BASE/scan/sync".toHttpUrl().newBuilder()
            .addQueryParameter("sid", sid)
            .build()
            .toString()

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}

/**
 * 从 WebView 子资源请求里提取二维码会话（纯逻辑，JVM 可单测）。
 *
 * 请求序列（每次 iframe 加载/刷新）：
 *   1. `/connect/qrpage?appid=..&return_url=..&rand_token=..` —— 携带会话参数；
 *   2. `/connect/qrimg?sid=..` —— 携带本次生成的二维码 sid。
 * 两者按序配对成 [QrAuthApi.QrTarget]；sid 随页面刷新不断更换，保留最近若干个。
 */
class QrTargetTracker(private val maxTargets: Int = 5) {

    private val targets = LinkedHashMap<String, QrAuthApi.QrTarget>()
    private var pendingAppId = ""
    private var pendingRandToken = ""
    private var pendingRetUrl = ""

    /** 最新捕获到的 sid（用于"复制微信授权链接"）。 */
    val latestSid: String? get() = synchronized(this) { targets.keys.lastOrNull() }

    fun snapshot(): List<QrAuthApi.QrTarget> = synchronized(this) { targets.values.toList() }

    /** 处理一条请求 URL；返回 true 表示捕获到新的二维码会话。 */
    fun onRequest(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (uri.host?.lowercase() != "sis.ustb.edu.cn") return false
        val query = parseQuery(uri.rawQuery)
        return when (uri.path) {
            "/connect/qrpage" -> {
                synchronized(this) {
                    pendingAppId = query["appid"].orEmpty()
                    pendingRandToken = query["rand_token"].orEmpty()
                    // return_url 是 URL 编码的 SSO 回调地址，解码后备用
                    pendingRetUrl = query["return_url"]?.let { decode(it) }.orEmpty()
                }
                false
            }
            "/connect/qrimg" -> {
                val sid = query["sid"]?.takeIf { it.isNotBlank() } ?: return false
                synchronized(this) {
                    if (pendingAppId.isBlank() || pendingRetUrl.isBlank()) return false
                    if (targets.containsKey(sid)) return false
                    targets[sid] = QrAuthApi.QrTarget(sid, pendingAppId, pendingRandToken, pendingRetUrl)
                    while (targets.size > maxTargets) {
                        targets.remove(targets.keys.first())
                    }
                }
                true
            }
            else -> false
        }
    }

    private companion object {
        fun parseQuery(raw: String?): Map<String, String> =
            raw?.split("&")?.mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }?.toMap().orEmpty()

        fun decode(value: String): String =
            runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
