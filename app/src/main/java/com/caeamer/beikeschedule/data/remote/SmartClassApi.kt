package com.caeamer.beikeschedule.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 贝壳教学平台（smartclass）无课教室接口。
 *
 * 与项目里"检查更新"用的是同一套 HTTP 方式（`HttpURLConnection`），
 * 不额外引入 HTTP 客户端 —— 只有 4 个接口，够用。
 *
 * **所有请求都要签名**（见 [SmartClassCrypto]），签名用服务器时间而非本机时间。
 * 签名参数由调用方通过 [SignedRequest] 注入，避免本类去管 key 的获取与缓存。
 */
class SmartClassApi(private val baseUrl: String = DEFAULT_BASE_URL) {

    /** 一次已签名的调用所需的上下文。 */
    data class SignedRequest(
        val csrkKey: String,
        val timeMillis: Long,
    )

    /**
     * 取 `/config.json` 原文（含加密的 `domainConfig`）。
     * 该接口**不需要签名**（实测：无 token 也能取到）。
     */
    suspend fun fetchDomainConfig(): String? = request(
        signed = null,
        path = "/config.json",
        method = "GET",
    ).body?.let { body ->
        runCatching {
            org.json.JSONObject(body).optString("domainConfig").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 取服务器当前毫秒时间，用于校正本机时钟；失败返回 null。
     * 该接口需要签名（实测：不带 token 返回"验证不通过"），所以必须用一个 key 先签——
     * 调用方在拿到 key 之前可以先不校正，拿到后再调。
     */
    suspend fun serverTimeMillis(signed: SignedRequest? = null): Long? {
        if (signed == null) return null
        val body = request(signed, "/Home/GettimeDif", "GET").body ?: return null
        return body.trim().toLongOrNull()?.takeIf { it > 1_000_000_000_000L }
    }

    /** 教学楼列表。 */
    suspend fun listBuildings(signed: SignedRequest): Result<List<SmartClassParser.Building>> =
        fetch(signed, "/general/api/open/building/listBuildings", "GET") { SmartClassParser.parseBuildings(it) }

    /** 节次类型列表。 */
    suspend fun listNodeTypes(signed: SignedRequest): Result<List<SmartClassParser.NodeType>> =
        fetch(signed, "/general/api/open/teachingCycle/listNodeTypes", "GET") { SmartClassParser.parseNodeTypes(it) }

    /**
     * 某栋楼、某种节次划分下的空教室。
     *
     * @param nodeId 传空串表示"全部时段"（实测：传单个 nodeId 只返回该时段）
     */
    suspend fun freeClassRooms(
        signed: SignedRequest,
        buildingId: String,
        cycleTypeId: String,
        nodeId: String = "",
    ): Result<List<SmartClassParser.RoomSlot>> = fetch(
        signed,
        "/general/api/classroom/freeClassRooms",
        "POST",
        buildJsonBody(buildingId, cycleTypeId, nodeId),
    ) { SmartClassParser.parseRoomSlots(it) }

    /** 统一的"取数据 + 解析"包装；解析为空或 code!=0 都算失败并带上服务端消息。 */
    private suspend fun <T> fetch(
        signed: SignedRequest,
        path: String,
        method: String,
        body: String? = null,
        parse: (String) -> List<T>,
    ): Result<List<T>> {
        val res = request(signed, path, method, body)
        val text = res.body ?: return Result.failure(SmartClassException("网络请求失败"))
        SmartClassParser.errorMessage(text)?.let {
            return Result.failure(SmartClassException(it, tokenRejected = SmartClassCrypto.isTokenRejected(text)))
        }
        return Result.success(parse(text))
    }

    private data class RawResponse(val code: Int, val body: String?)

    private suspend fun request(
        signed: SignedRequest?,
        path: String,
        method: String,
        body: String? = null,
    ): RawResponse = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(if (signed == null) baseUrl + path else SmartClassCrypto.sign(baseUrl + path, signed.csrkKey, signed.timeMillis))
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", baseUrl + REFERER_PATH)
                setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json;charset=utf8")
                }
            }
            body?.let { conn.outputStream.use { os -> os.write(it.toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            // 4xx/5xx 时错误详情在 errorStream 里；只读 inputStream 会拿到 null 而丢失原因
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText)
            RawResponse(code, text)
        } catch (e: Exception) {
            RawResponse(-1, null)
        } finally {
            conn?.disconnect()
        }
    }

    /** 手工拼 JSON 请求体：字段固定三个且都是字符串，为它引序列化库不划算。 */
    private fun buildJsonBody(buildingId: String, cycleTypeId: String, nodeId: String): String =
        buildString {
            append('{')
            append("\"buildingId\":").append(quote(buildingId)).append(',')
            append("\"cycleTypeId\":").append(quote(cycleTypeId)).append(',')
            append("\"nodeId\":").append(quote(nodeId))
            append('}')
        }

    /** 转义 JSON 字符串字面量。这些值来自服务端且是 hex，正常不含特殊字符，但仍需防御。 */
    private fun quote(raw: String): String = buildString {
        append('"')
        for (c in raw) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://ustb.smartclass.cn"

        private const val REFERER_PATH = "/ustb/ClassRoom.aspx"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * smartclass 接口失败。
 *
 * @param tokenRejected 是否为"签名被拒"（`csrf key validate error`）。
 *   上层据此决定要不要丢弃缓存的 `csrkKey` 并重试一次 —— 这通常意味着服务端
 *   轮换了 key，重取即可自愈；把它和普通网络错误混在一起会让用户看到无意义的报错。
 */
class SmartClassException(
    override val message: String,
    val tokenRejected: Boolean = false,
) : Exception(message)
