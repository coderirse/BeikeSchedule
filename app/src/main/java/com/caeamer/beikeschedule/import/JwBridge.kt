package com.caeamer.beikeschedule.import

import android.webkit.JavascriptInterface

/**
 * WebView `@JavascriptInterface` 桥的公共基类。
 *
 * 存在的理由有两个：
 * 1. **让 lint 的 `JavascriptInterface` 检查生效**。[JwWebView] 的 `bridge` 参数此前是
 *    `Any`，lint 看不到实际类型，于是报 "None of the methods in the added interface
 *    (Object) have been annotated"——这条检查本来是防止"桥方法忘了加注解导致在
 *    API 17+ 上静默不可见"的，用 `Any` 等于把它关掉了。
 * 2. **承载共用的 `onError`**：两个桥的 JS 侧都调 `onError`，此前各自实现一份。
 *
 * 子类各自声明自己的结果回调名（`onResult` / `onGradesResult`）——两边的 JS 脚本
 * 就是这么调的，不做统一。
 */
sealed class JwBridge {

    /** 脚本侧主动报错（`window.BeikeXxx.onError(msg)`）。 */
    @JavascriptInterface
    open fun onError(message: String) = onFailure(message)

    /** 抓取失败的统一出口，由子类构造时注入。 */
    protected abstract val onFailure: (String) -> Unit
}
