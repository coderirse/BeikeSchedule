package com.caeamer.beikeschedule.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 云同步 REST 客户端（api.caeamer.com，服务端是 showwe 里的 /api/bs 模块）。
 *
 * 与 showwe 社区客户端一样"手写 REST 封装、无 Retrofit"：接口只有五个，
 * 一个 OkHttp 单例加几个函数比引入一套注解体系更可控。所有挂起函数都切到 IO。
 */
object CloudApi {

    // api.caeamer.com 未做 ICP 备案，阿里云拦截走域名的流量（HTTPS 握手被 reset），
    // 备案完成前与 showwe 客户端同策略：IP 直连明文 HTTP（仅此 IP 在
    // network_security_config.xml 中放行明文）。备案后换回 https://api.caeamer.com。
    private const val BASE_URL = "http://112.125.88.178"
    private const val PATH_LATEST = "/api/bs/app/latest"
    private const val PATH_LOGIN = "/api/bs/auth/login"
    private const val PATH_BACKUP = "/api/bs/backup"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 学号登录结果：服务端登录即注册，返回 token（账号 = 学号，无密码）。 */
    @Serializable
    data class LoginResult(val token: String, val xh: String, val name: String = "")

    @Serializable
    data class LatestVersion(
        val versionCode: Int = 0,
        val versionName: String = "",
        val changelog: String = "",
        val force: Boolean = false,
        val size: Long = 0,
        val url: String = "",
        /** Ed25519 分离签名（Base64），覆盖上述字段的规范化 JSON，见 [UpdateSignature]。 */
        val sig: String = "",
        /** APK 的 SHA-256（hex 小写）。仅当签名按新约定覆盖它时才可信（见 UpdateSignature.verifyDetailed）。 */
        val apkSha256: String = "",
    )

    @Serializable
    data class BackupEnvelope(
        val exists: Boolean = false,
        val updatedAt: Long = 0,
        val sizeBytes: Long = 0,
        val appVersionCode: Int = 0,
        // 快照本体保持为原始 JSON 对象，由 CloudSnapshot 反序列化，避免这里耦合字段
        val data: JsonObject? = null,
        /** 签名覆盖的**原始快照字节串**（服务端原样下发）；验签走它而不是重序列化 data。 */
        val snapshot: String? = null,
        /** 服务端 Ed25519 签名（Base64），覆盖 [snapshot] 的 UTF-8 字节，见 [BackupSignature]。 */
        val sig: String = "",
    )

    /**
     * 学号登录（登录即注册）。调用前提：教务统一认证已在 WebView 里成功登录。
     *
     * [jwTicket] = 教务 SESSION 的密封票据（见 [JwSessionTicket]）：服务端拿它向教务系统
     * 核实"登录的人确实是这个学号"。取不到票据时传 null，服务端会明确要求升级重试
     * （旧版仅校验学号+姓名，等于谁都能读走别人的备份，服务端已不再为新账号放行）。
     */
    suspend fun login(xh: String, xm: String, jwTicket: String? = null): LoginResult =
        withContext(Dispatchers.IO) {
        val body = loginBody(xh, xm, jwTicket)
        val response = post(PATH_LOGIN, body, token = null)
        parseOrThrow(response, PATH_LOGIN) { text ->
            json.decodeFromString(LoginResult.serializer(), text)
        }
    }

    /** 登录请求体（纯逻辑，JVM 可单测）：票据为空时不写字段，避免发 `"jwTicket":null`。 */
    internal fun loginBody(xh: String, xm: String, jwTicket: String?): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("xh", xh)
                put("xm", xm)
                if (!jwTicket.isNullOrBlank()) put("jwTicket", jwTicket)
            },
        )

    suspend fun latestVersion(): LatestVersion = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE_URL$PATH_LATEST").get().build()
        parseOrThrow(execute(request), PATH_LATEST) { text ->
            json.decodeFromString(LatestVersion.serializer(), text)
        }
    }

    suspend fun getBackup(token: String): BackupEnvelope = withContext(Dispatchers.IO) {
        val request = authorized(Request.Builder().url("$BASE_URL$PATH_BACKUP").get(), token).build()
        parseOrThrow(execute(request), PATH_BACKUP, authRequired = true) { text ->
            json.decodeFromString(BackupEnvelope.serializer(), text)
        }
    }

    /** 上传整包快照。snapshotJson 已序列化好的快照字符串，服务端原样存取。 */
    suspend fun putBackup(token: String, snapshotJson: String, appVersionCode: Int) =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("appVersionCode", appVersionCode)
                    put("data", json.parseToJsonElement(snapshotJson))
                },
            )
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = authorized(
                Request.Builder().url("$BASE_URL$PATH_BACKUP").put(body.toRequestBody(mediaType)),
                token,
            ).build()
            execute(request).use { resp ->
                if (!resp.isSuccessful) throw httpError(resp, authRequired = true)
            }
        }

    suspend fun deleteBackup(token: String) = withContext(Dispatchers.IO) {
        val request = authorized(
            Request.Builder().url("$BASE_URL$PATH_BACKUP").delete(),
            token,
        ).build()
        execute(request).use { resp ->
            if (!resp.isSuccessful) throw httpError(resp, authRequired = true)
        }
    }

    // —— 内部工具 ——

    private fun post(path: String, body: String, token: String?): okhttp3.Response {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val builder = Request.Builder().url("$BASE_URL$path").post(body.toRequestBody(mediaType))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        return execute(builder.build())
    }

    private fun authorized(builder: Request.Builder, token: String): Request.Builder =
        builder.header("Authorization", "Bearer $token")

    private fun execute(request: Request): okhttp3.Response =
        try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            // 协程取消不是网络失败：包成 IOException 会破坏结构化取消
            if (e is CancellationException) throw e
            throw networkFailure(e)
        }

    /**
     * 传输层异常 → 用户可行动的文案。类型映射与 SmartClassApi 对齐，
     * 不再把超时/TLS/取消都压成「网络不可用」。
     */
    internal fun networkFailure(e: Exception): IOException = when (e) {
        is UnknownHostException -> IOException("网络不可用，请检查网络后重试", e)
        is SocketTimeoutException -> IOException("连接超时，请稍后重试", e)
        is ConnectException -> IOException("无法连接到服务器，请稍后重试", e)
        is SSLException -> IOException("安全连接失败，请稍后重试", e)
        is IOException -> IOException("网络请求失败，请稍后重试", e)
        else -> IOException("网络请求失败，请稍后重试", e)
    }

    private inline fun <T> parseOrThrow(
        response: okhttp3.Response,
        path: String,
        authRequired: Boolean = false,
        parse: (String) -> T,
    ): T = response.use { resp ->
        if (!resp.isSuccessful) throw httpError(resp, authRequired, path)
        val text = resp.body?.string().orEmpty()
        runCatching { parse(text) }.getOrElse {
            throw IOException("$path 响应格式异常")
        }
    }

    /**
     * HTTP 错误 → 异常。带 token 的接口 401 映射为 [CloudAuthException]，
     * 上层据此清本地登录态并引导重新登录，而不是静默当网络失败重试。
     */
    private fun httpError(
        resp: okhttp3.Response,
        authRequired: Boolean,
        @Suppress("UNUSED_PARAMETER") path: String = "",
    ): IOException {
        val message = messageOf(resp)
        if (authRequired && resp.code == 401) {
            return CloudAuthException(message.ifBlank { "登录已过期，请重新登录" })
        }
        return IOException("${message}（HTTP ${resp.code}）")
    }

    /** 服务端错误信息提取（{message: "..."}），取不到用通用文案。 */
    private fun messageOf(resp: okhttp3.Response): String = runCatching {
        val text = resp.body?.string().orEmpty()
        json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content
    }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "请求失败"
}

/**
 * 云 token 失效（带鉴权接口返回 401）。
 * 上层应清本地登录态并引导重新登录，暂停自动同步。
 */
class CloudAuthException(message: String) : IOException(message)
