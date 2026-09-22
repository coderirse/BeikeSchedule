package com.caeamer.beikeschedule.import

import org.json.JSONArray
import org.json.JSONObject

/**
 * 注入脚本与 Kotlin 之间的消息桥。
 *
 * **为什么不是 `@JavascriptInterface`**：`addJavascriptInterface` 把桥注入 WebView 里
 * **每一个 frame**，而导航白名单（`shouldOverrideUrlLoading`）只覆盖主框架导航——
 * iframe 导航、3xx 重定向目标与子资源请求都管不到。于是白名单页面里嵌的第三方 iframe
 * 也能调用桥，把伪造的"抓取结果"直接灌进 Room。
 * 现在改用 `WebViewCompat.addWebMessageListener`：可见范围由平台按 origin 规则收口，
 * 回调里再校验"主框架 + 教务域名"，两层都在平台侧，注入脚本无法绕过。
 *
 * 消息格式统一为 JSON 信封：`{"fn":"onResult","args":["...", ...]}`，
 * 由 [dispatch] 分发到子类的 [onMessage] / [onError]。
 */
interface JwBridge {

    /** 脚本侧主动报错（`fn = "onError"`）。 */
    fun onError(message: String)

    /** 结果回传（`fn` 为子类约定的名字，如 onResult / onGradesResult）。 */
    fun onMessage(fn: String, args: List<String>)

    /** 解析并分发一条 JS 消息；载荷非法时静默忽略（页面异常不该让 App 崩）。 */
    fun dispatch(payload: String) {
        val (fn, args) = parseEnvelope(payload) ?: return
        if (fn == "onError") onError(args.firstOrNull().orEmpty()) else onMessage(fn, args)
    }

    private fun parseEnvelope(payload: String): Pair<String, List<String>>? = runCatching {
        val obj = JSONObject(payload)
        val fn = obj.optString("fn")
        if (fn.isBlank()) return@runCatching null
        val arr = obj.optJSONArray("args") ?: JSONArray()
        fn to (0 until arr.length()).map { i -> arr.optString(i, "") }
    }.getOrNull()
}
