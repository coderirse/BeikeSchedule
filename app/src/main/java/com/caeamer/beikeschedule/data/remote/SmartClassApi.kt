package com.caeamer.beikeschedule.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 一次已签名的调用所需的上下文。
 *
 * @param csrkKey 服务端下发的签名密钥（见 [SmartClassCrypto]）
 * @param timeMillis 用于签名的时间戳（**必须是服务器时间**，见 [SmartClassKeyProvider.signingTimeMillis]）
 */
data class SignedRequest(
    val csrkKey: String,
    val timeMillis: Long,
)

/**
 * 无课教室的数据源。
 *
 * 抽成接口的目的只有一个：让 [com.caeamer.beikeschedule.data.repo.FreeRoomRepository]
 * 的"签名被拒 → 丢弃缓存 → 重试一次"这条自愈路径可以被单测覆盖
 * （它曾经因为漏了一个接口而整条不可达，见 `FreeRoomRepositoryTest`）。
 */
interface SmartClassDataSource {
    /** 取 `/config.json` 原文里的 `domainConfig`（加密的 key 段）。无需签名。 */
    suspend fun fetchDomainConfig(): String?

    /**
     * 取服务器时间（毫秒），用于校正本机时钟。
     *
     * 实现走 `/config.json` 的 HTTP `Date` 响应头：该请求**不需要签名**，
     * 因此不存在"要签名才能校时、要校时才能签名"的鸡生蛋问题。
     * 失败返回 null（调用方保留上一次的偏移量）。
     */
    suspend fun serverDateMillis(): Long?

    /**
     * 备用的校时接口 `/Home/GettimeDif`；失败返回 null。
     *
     * 站点自己的前端也是这么用的（2026-09 在 `Classroom.aspx` 的内联脚本里确认）：
     * ```js
     * $.ajax({ url: getcsrf('/Home/GettimeDif'), success: function (data) {
     *     localStorage.setItem("TimeDif", parseInt(data) - new Date().getTime()) } })
     * ```
     * 即返回**绝对毫秒时间戳**（不是时间差），校验 `> 1e12` 因此是正确且有必要的。
     */
    suspend fun serverTimeMillis(signed: SignedRequest): Long?

    /** 教学楼列表。 */
    suspend fun listBuildings(signed: SignedRequest): Result<List<SmartClassParser.Building>>

    /** 节次类型列表。 */
    suspend fun listNodeTypes(signed: SignedRequest): Result<List<SmartClassParser.NodeType>>

    /** 某栋楼、某种节次划分下的空教室（`nodeId` 传空串 = 全部时段）。 */
    suspend fun freeClassRooms(
        signed: SignedRequest,
        buildingId: String,
        cycleTypeId: String,
        nodeId: String = "",
    ): Result<List<SmartClassParser.RoomSlot>>
}

/**
 * 贝壳教学平台（smartclass）无课教室接口。
 *
 * 与项目里"检查更新"用的是同一套 HTTP 方式（`HttpURLConnection`），
 * 不额外引入 HTTP 客户端 —— 只有 4 个接口，够用。
 *
 * **所有请求都要签名**（见 [SmartClassCrypto]），签名用服务器时间而非本机时间。
 * 签名参数由调用方通过 [SignedRequest] 注入，避免本类去管 key 的获取与缓存。
 */
class SmartClassApi(private val baseUrl: String = DEFAULT_BASE_URL) : SmartClassDataSource {

    /**
     * 取 `/config.json` 原文（含加密的 `domainConfig`）。
     * 该接口**不需要签名**（实测：无 token 也能取到）。
     */
    override suspend fun fetchDomainConfig(): String? = request(
        signed = null,
        path = "/config.json",
        method = "GET",
    ).body?.let { body ->
        runCatching {
            JSONObject(body).optString("domainConfig").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 服务器当前时间（毫秒）：取 `/config.json` 的 `Date` 响应头，无需签名。 */
    override suspend fun serverDateMillis(): Long? =
        request(signed = null, path = "/config.json", method = "GET").dateMillis?.takeIf { it > 0L }

    /** 服务器当前毫秒时间（备用校时接口）；失败返回 null。 */
    override suspend fun serverTimeMillis(signed: SignedRequest): Long? {
        val body = request(signed, "/Home/GettimeDif", "GET").body ?: return null
        return body.trim().toLongOrNull()?.takeIf { it > 1_000_000_000_000L }
    }

    /** 教学楼列表。 */
    override suspend fun listBuildings(signed: SignedRequest): Result<List<SmartClassParser.Building>> =
        fetch(signed, "/general/api/open/building/listBuildings", "GET") { SmartClassParser.parseBuildings(it) }

    /** 节次类型列表。 */
    override suspend fun listNodeTypes(signed: SignedRequest): Result<List<SmartClassParser.NodeType>> =
        fetch(signed, "/general/api/open/teachingCycle/listNodeTypes", "GET") { SmartClassParser.parseNodeTypes(it) }

    /**
     * 某栋楼、某种节次划分下的空教室。
     *
     * @param nodeId 传空串表示"全部时段"（实测：传单个 nodeId 只返回该时段；
     *   站点自己的前端也是 `nodeId: nodeID == -1 ? '' : nodeID`）
     */
    override suspend fun freeClassRooms(
        signed: SignedRequest,
        buildingId: String,
        cycleTypeId: String,
        nodeId: String,
    ): Result<List<SmartClassParser.RoomSlot>> = fetch(
        signed,
        "/general/api/classroom/freeClassRooms",
        "POST",
        buildJsonBody(buildingId, cycleTypeId, nodeId),
    ) { SmartClassParser.parseRoomSlots(it) }

    /**
     * 统一的"取数据 + 解析"包装。
     *
     * 失败有三种来源，**都必须变成 failure**：
     * 1. 传输层异常（DNS/超时/TLS）→ 按类型给人话；
     * 2. HTTP 状态码非 2xx → 带状态码报错（网关维护页、WAF 挑战页都走这里）；
     * 3. body 不是合法 JSON 或 `code != 0` → 带服务端消息报错。
     *
     * 此前 2 与 3 会被静默当成"查询成功但结果为空"，用户看到的是
     * "当前没有查询到无课教室"这种**貌似正常但错误**的结论。
     */
    private suspend fun <T> fetch(
        signed: SignedRequest,
        path: String,
        method: String,
        body: String? = null,
        parse: (String) -> List<T>,
    ): Result<List<T>> {
        val res = request(signed, path, method, body)
        if (res.code < 0) {
            return Result.failure(SmartClassException(networkMessage(res.error), cause = res.error))
        }
        if (res.code !in 200..299) {
            return Result.failure(
                SmartClassException(
                    httpMessage(res.code),
                    tokenRejected = SmartClassCrypto.isTokenRejected(res.body.orEmpty()),
                    cause = res.error,
                ),
            )
        }
        val text = res.body ?: return Result.failure(SmartClassException("响应为空，请稍后重试"))
        SmartClassParser.errorMessage(text)?.let {
            return Result.failure(
                SmartClassException(it, tokenRejected = SmartClassCrypto.isTokenRejected(text)),
            )
        }
        return Result.success(parse(text))
    }

    private data class RawResponse(val code: Int, val body: String?, val dateMillis: Long? = null, val error: Exception? = null)

    private suspend fun request(
        signed: SignedRequest?,
        path: String,
        method: String,
        body: String? = null,
    ): RawResponse = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            // signOrNull：签名生成失败时**不发请求**直接报错——发无签名的请求必然被
            // 服务端拒绝，"签名被拒重校"自愈还会把 key 格式坏误判成 key 轮换反复重拉
            val url = if (signed == null) baseUrl + path
            else SmartClassCrypto.signOrNull(baseUrl + path, signed.csrkKey, signed.timeMillis)
                ?: throw java.io.IOException("教务接口签名失败（密钥异常），请稍后重试")
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
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
            // 服务器时间基准：Date 头在响应码可读之后、断开之前取
            val dateMillis = conn.getHeaderFieldDate("Date", 0L)
            // 4xx/5xx 时错误详情在 errorStream 里；只读 inputStream 会拿到 null 而丢失原因
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText)
            RawResponse(code, text, dateMillis)
        } catch (e: Exception) {
            // 协程取消不是网络失败：吞掉它会破坏结构化取消（调用方以为"请求失败"继续跑失败分支），
            // 必须原样放行。其余异常带出去：调用方要按类型给"网络不可用/超时"这类可行动提示。
            if (e is kotlinx.coroutines.CancellationException) throw e
            RawResponse(-1, null, null, e)
        } finally {
            conn?.disconnect()
        }
    }

    /** 把传输层异常翻成用户能行动的中文；不再是统一的"网络请求失败"。 */
    private fun networkMessage(e: Exception?): String = when (e) {
        is UnknownHostException -> "网络不可用，请检查网络连接后重试"
        is SocketTimeoutException -> "连接超时，请稍后重试"
        is ConnectException -> "无法连接到服务器，请稍后重试"
        is SSLException -> "安全连接失败，请稍后重试"
        null -> "网络请求失败，请稍后重试"
        else -> "网络请求失败，请稍后重试"
    }

    private fun httpMessage(code: Int): String = when {
        code >= 500 -> "服务暂时不可用（HTTP $code），请稍后重试"
        code == 404 -> "接口不存在（HTTP 404），可能是平台改版，请检查 App 更新"
        else -> "请求被拒绝（HTTP $code）"
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
 * @param cause 原始异常（网络/解析），用于日志定位；面向用户的文案在 message 里。
 */
class SmartClassException(
    override val message: String,
    val tokenRejected: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
