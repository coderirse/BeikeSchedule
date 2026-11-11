package com.caeamer.beikeschedule.ui.pet.ai

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.time.Instant
import java.util.concurrent.TimeUnit

data class AiToolCall(val id: String, val name: String, val arguments: String)
data class AiReply(val text: String, val tools: List<AiToolCall>, val sources: List<AiSource>, val tokens: Int = 0)

object AiProtocol {
    val json = Json { ignoreUnknownKeys = true }
    fun endpoint(base: String, route: String): String {
        val url = base.trim().toHttpUrlOrNull() ?: throw PetAiException("请输入有效的 HTTPS API 地址")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw PetAiException("API 地址需使用 HTTPS，且不能包含账号、查询参数或片段")
        }
        val root = url.toString().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models")
        return "$root/$route"
    }
    fun endpoint(provider: AiProvider, route: String): String {
        val target = endpoint(provider.baseUrl, route)
        if (provider.vendor != AiVendor.CUSTOM && target != endpoint(provider.vendor.baseUrl, route)) {
            throw PetAiException("预设供应商使用官方地址；其他服务地址请改选自定义供应商")
        }
        return target
    }
    fun obj(text: String): JsonObject = try { json.parseToJsonElement(text).jsonObject }
        catch (_: Exception) { throw PetAiException("供应商返回了无法解析的内容，请检查 API 地址和接口兼容性") }
    fun error(code: Int): String = when (code) {
        401, 403 -> "API Key 无效、无权限，或尚未开通所选模型/联网服务"
        402 -> "供应商余额或额度不足"
        404 -> "未找到接口或模型，请检查服务地址和模型名"
        408, 504 -> "供应商请求超时，请稍后重试"
        429 -> "供应商限流或额度已用完，请稍后重试"
        in 500..599 -> "供应商服务暂时不可用（$code）"
        else -> "供应商拒绝请求（$code），请检查模型和接口能力"
    }
    fun message(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    fun sources(value: JsonElement?): List<AiSource> = (value as? JsonArray).orEmpty().mapNotNull { entry ->
        val item = entry as? JsonObject ?: return@mapNotNull null
        val citation = item["url_citation"] as? JsonObject ?: item
        val url = citation["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val parsed = url.toHttpUrlOrNull() ?: return@mapNotNull null
        if (parsed.scheme !in setOf("http", "https") || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return@mapNotNull null
        AiSource(citation["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { parsed.host }, url,
            citation["publish_time"]?.jsonPrimitive?.contentOrNull ?: citation["date"]?.jsonPrimitive?.contentOrNull.orEmpty(), Instant.now().toString())
    }.distinctBy { it.url }.take(12)
}

/** 流式解码与 Android 无关，便于用供应商样本验证分片参数、来源和结束状态。 */
class AiStreamDecoder {
    private data class Tool(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
    private val calls = sortedMapOf<Int, Tool>()
    private val text = StringBuilder()
    private val sources = mutableListOf<AiSource>()
    private var tokens = 0
    var ended: Boolean = false; private set
    var done: Boolean = false; private set
    fun accept(data: String): String {
        if (data.trim() == "[DONE]") { ended = true; done = true; return "" }
        val body = AiProtocol.obj(data)
        if (body["error"] != null) throw PetAiException("供应商生成失败，请检查模型配置后重试")
        tokens = body["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: tokens
        val choice = (body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return ""
        if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull in setOf("length", "content_filter", "insufficient_system_resource")) {
            throw PetAiException("供应商未能完整生成回复，可重试或更换模型")
        }
        val delta = choice["delta"] as? JsonObject ?: choice["message"] as? JsonObject ?: return ""
        val fragment = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        text.append(fragment)
        if (text.length > 32000) throw PetAiException("回复过长，已停止生成")
        sources += AiProtocol.sources(delta["annotations"] ?: choice["annotations"])
        (delta["tool_calls"] as? JsonArray)?.forEach { raw ->
            val call = raw.jsonObject
            val index = call["index"]?.jsonPrimitive?.intOrNull ?: calls.size
            if (index !in 0..15) throw PetAiException("工具调用数量超出限制，已停止生成")
            val tool = calls.getOrPut(index) { Tool() }
            call["id"]?.jsonPrimitive?.contentOrNull?.let { tool.id = it }
            (call["function"] as? JsonObject)?.let { fn ->
                fn["name"]?.jsonPrimitive?.contentOrNull?.let { tool.name += it }
                fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { tool.args.append(it) }
                if (tool.args.length > 16000) throw PetAiException("工具请求过长，已停止")
            }
        }
        if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull in setOf("stop", "tool_calls")) ended = true
        return fragment
    }
    fun result(): AiReply {
        if (!ended) throw PetAiException("连接中断，回复未完成，请重试")
        return AiReply(text.toString(), calls.values.map { AiToolCall(it.id, it.name, it.args.toString()) }, sources.distinctBy { it.url }, tokens)
    }
}

class PetAiClient(private val calls: Call.Factory = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    private val media = "application/json; charset=utf-8".toMediaType()
    private fun boundedBody(response: Response, limit: Long = 2_000_000): String {
        val source = response.body?.source() ?: throw PetAiException("供应商返回空内容")
        source.request(limit + 1)
        if (source.buffer.size > limit) throw PetAiException("供应商响应过大，已停止")
        return source.readUtf8()
    }
    private suspend fun <T> execute(request: Request, consume: (Response) -> T): T = coroutineScope {
        val call = calls.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try { withContext(Dispatchers.IO) {
            try { call.execute().use { response ->
                if (!response.isSuccessful) throw PetAiException(AiProtocol.error(response.code), response.code)
                consume(response)
            } } catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
        } } finally { cancellation.cancel() }
    }
    private fun request(url: String, key: String, body: JsonObject? = null): Request {
        if (key.isBlank() || key.any { it == '\r' || it == '\n' }) throw PetAiException("请先填写有效的 API Key")
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${key.trim()}").header("Accept", "application/json")
        if (body != null) builder.post(body.toString().toRequestBody(media))
        return builder.build()
    }
    suspend fun models(provider: AiProvider): List<String> = execute(request(AiProtocol.endpoint(provider, "models"), provider.apiKey)) {
        val body = boundedBody(it, 1_000_000)
        (AiProtocol.obj(body)["data"] as? JsonArray).orEmpty().mapNotNull { model -> model.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .distinct().sorted().take(200)
    }
    suspend fun chat(provider: AiProvider, messages: List<JsonObject>, tools: List<JsonObject>, onText: (String) -> Unit): AiReply {
        val body = buildJsonObject {
            put("model", provider.model.trim()); put("messages", JsonArray(messages)); put("stream", true); put("max_tokens", 2048)
            if (tools.isNotEmpty()) { put("tools", JsonArray(tools)); put("tool_choice", "auto") }
            if (provider.vendor != AiVendor.CUSTOM) put("thinking", buildJsonObject { put("type", "disabled") })
        }
        return execute(request(AiProtocol.endpoint(provider, "chat/completions"), provider.apiKey, body)) { response ->
            val source = response.body?.source() ?: throw PetAiException("供应商返回空回复")
            val decoder = AiStreamDecoder()
            if (response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                val event = StringBuilder()
                var total = 0L
                fun flush() { if (event.isNotEmpty()) { val fragment = decoder.accept(event.toString().trimEnd()); if (fragment.isNotEmpty()) onText(fragment); event.clear() } }
                while (!decoder.done && !source.exhausted()) {
                    val newline = source.indexOf('\n'.code.toByte(), 0, 1_000_001)
                    val line = if (newline >= 0) source.readUtf8LineStrict(1_000_000) else {
                        if (source.buffer.size > 1_000_000) throw PetAiException("供应商数据行过长，已停止")
                        source.readUtf8()
                    }
                    total += line.length
                    if (total > 2_000_000) throw PetAiException("供应商响应过大，已停止")
                    if (line.isEmpty()) flush() else if (line.startsWith("data:")) event.append(line.removePrefix("data:").trimStart()).append('\n')
                }
                flush()
            } else {
                // 一些兼容服务忽略 stream=true，仍接受合法的非流式 JSON。
                val text = boundedBody(response)
                val fragment = decoder.accept(text)
                if (fragment.isNotEmpty()) onText(fragment)
            }
            decoder.result()
        }
    }

    suspend fun search(key: String, query: String): Pair<String, List<AiSource>> {
        val body = buildJsonObject { put("query", query.trim().take(128)); put("count", 5); put("need_content", false) }
        return execute(request("https://api.deepseek.com/v1/tools/search", key, body)) { response ->
            val raw = boundedBody(response, 1_000_000)
            val results = AiProtocol.obj(raw)["results"] as? JsonArray ?: throw PetAiException("搜索接口返回无效结果")
            val selected = results.take(5).map { it.jsonObject }
            val sources = AiProtocol.sources(JsonArray(selected))
            val publicText = buildJsonObject {
                put("retrievedAt", Instant.now().toString())
                put("results", JsonArray(selected.map { item -> buildJsonObject {
                    listOf("title", "url", "date").forEach { k -> item[k]?.let { put(k, it) } }
                    put("summary", (item["summary"] ?: item["snippet"])?.jsonPrimitive?.contentOrNull.orEmpty().take(2500))
                } }))
            }.toString()
            publicText to sources
        }
    }
}
