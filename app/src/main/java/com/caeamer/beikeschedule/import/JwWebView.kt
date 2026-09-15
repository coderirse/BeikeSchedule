package com.caeamer.beikeschedule.import

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext

private const val JW_HOME = "https://byyt.ustb.edu.cn"
private const val MAIN_PAGE_MARK = "/authentication/main"

/**
 * 教务页面渲染修正脚本，解决 WebView 白页：
 * 1. 页面 rem 适配按 1920px 桌面设计（fontSize = clientWidth/1920*37.5），
 *    而其 meta viewport 是 width=device-width → 手机上布局宽 360px、根字体仅 7px，
 *    须改写为 width=1440 恢复桌面比例（useWideViewPort 会被 meta 覆盖，只能注入改写）；
 * 2. .page{height:100vh} 在此 WebView 中 vh/百分比高度均算出 0（ICB 高度异常），
 *    导致 #app 高度 0 且 overflow:hidden 裁掉全部内容，须用 innerHeight 像素值补上。
 * 页面脚本监听视口变化会自动重算 rem，注入后无需刷新。脚本幂等，每次导航重复注入。
 */
private const val PAGE_FIX_JS = """
(function () {
  if (window.__bkPageFixInstalled) return;
  window.__bkPageFixInstalled = true;
  function fixAll() {
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) {
      m = document.createElement('meta');
      m.name = 'viewport';
      (document.head || document.documentElement).appendChild(m);
    }
    if (m.getAttribute('content') !== 'width=1440') m.setAttribute('content', 'width=1440');
    var app = document.querySelector('#app');
    if (app && app.getBoundingClientRect().height === 0) {
      app.style.setProperty('height', window.innerHeight + 'px', 'important');
    }
  }
  fixAll();
  new MutationObserver(fixAll).observe(document.documentElement, { childList: true, subtree: true });
  document.addEventListener('DOMContentLoaded', fixAll);
  setTimeout(fixAll, 500);
  setTimeout(fixAll, 1500);
})();
"""

/**
 * 教务系统 WebView（导入页/成绩页共用）：
 * 登录统一认证 → 到达主页后回调 onMainPage（由调用方注入抓取脚本）。
 * @param bridge addJavascriptInterface 的桥对象（JwImportBridge/GradesBridge）
 * @param bridgeName 桥在 JS 侧的名字（"BeikeImport"/"BeikeGrades"）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwWebView(
    bridge: JwBridge,
    bridgeName: String,
    onMainPage: () -> Unit,
    onCreated: (WebView) -> Unit = {},
    onPageStarted: () -> Unit = {},
    onPageError: (String) -> Unit = {},
    onPageProgress: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            // 仅调试包允许 DevTools 远程调试 WebView
            if (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            WebView(it).apply {
                // 教务登录页 PC 布局加载慢，且默认白背景刺眼；设淡暖色底让加载过程更柔和
                setBackgroundColor(android.graphics.Color.parseColor("#F5EFEF"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 配合 PAGE_FIX_JS 的 meta 改写：宽视口布局 + 总览缩放把 PC 页面缩放到一屏
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // 显式关掉文件/content 访问与混合内容：这些在 targetSdk 37 下本就是安全默认值，
                // 但"依赖平台默认值"是隐式契约，写出来才能在将来被审计时确认意图。
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                CookieManager.getInstance().setAcceptCookie(true)
                // 第三方 cookie 不必要：jw_import.js / jw_grades.js 的所有 fetch 都是
                // credentials: 'same-origin'，关掉它只减少暴露面。
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                addJavascriptInterface(bridge, bridgeName)
                webViewClient = object : WebViewClient() {
                    // 域名白名单：addJavascriptInterface 的桥对 WebView 里所有页面生效，
                    // 站外链接一律转交系统浏览器，避免第三方页面调用桥伪造抓取数据。
                    //
                    // **必须失败关闭**：此前 host 为 null 时 `?: return false` 会放行
                    // file:/content:/data:/blob: 这类无 host 的 URL 进入 WebView；
                    // 也没有 scheme 检查，http:// 的站内地址同样能过。
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        if (url.scheme != "https") return true          // 拒绝加载，不交接
                        val host = url.host?.lowercase() ?: return true // 无 host 一律拒绝
                        if (isJwHost(host)) return false
                        return runCatching {
                            view.context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, url),
                            )
                            true
                        }.getOrDefault(true)
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        onPageStarted()
                        // 尽早注入，MutationObserver 会在 meta 标签解析出来时立即改写。
                        // 只对白名单域名注入：此前对每个页面（含站外页）都注入。
                        if (isAllowlistedUrl(url)) view.evaluateJavascript(PAGE_FIX_JS, null)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // 兜底注入（脚本幂等），覆盖 onPageStarted 时机过晚的情况
                        if (isAllowlistedUrl(url)) view.evaluateJavascript(PAGE_FIX_JS, null)
                        // 主页面判定改为 host + path **精确**匹配：
                        // 此前是 url.contains("/authentication/main")，任意域名下含该路径的
                        // URL（如 https://evil.example/authentication/main）都会触发抓取脚本注入。
                        if (isMainPageUrl(url)) {
                            view.post { onMainPage() }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            onPageError("页面加载失败：${error.description}（请检查网络/VPN后重进本页）")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        errorResponse: android.webkit.WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) {
                            onPageError("页面返回错误：HTTP ${errorResponse.statusCode}")
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView,
                        handler: android.webkit.SslErrorHandler,
                        error: android.net.http.SslError,
                    ) {
                        handler.cancel()
                        onPageError("SSL 证书校验失败（${error.primaryError}），请检查网络/VPN")
                    }
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        onPageProgress(newProgress)
                    }
                }
                loadUrl(JW_HOME)
                onCreated(this)
            }
        },
        // WebView 必须显式销毁：AndroidView 离开组合时若只丢掉引用，持有 Activity context 的
        // WebView 与其 JS 定时器（PAGE_FIX_JS 里的 MutationObserver / setTimeout）会一起泄漏。
        // 导入页的"预览 → 重新抓取"会反复创建新实例，成绩页每次抓取完成后也会销毁一个。
        onRelease = { view ->
            runCatching { view.removeJavascriptInterface(bridgeName) }
            runCatching { view.stopLoading() }
            runCatching { view.loadUrl("about:blank") }
            runCatching { view.destroy() }
        },
    )
}

/** 教务系统域名白名单：ustb.edu.cn 及其子域。前导点保证 evilustb.edu.cn 不匹配。 */
internal fun isJwHost(host: String): Boolean =
    host == "ustb.edu.cn" || host.endsWith(".ustb.edu.cn")

/** URL 是否属于白名单域名（解析失败按不在白名单处理）。 */
private fun isAllowlistedUrl(url: String): Boolean =
    runCatching { android.net.Uri.parse(url) }.getOrNull()
        ?.takeIf { it.scheme == "https" }
        ?.host?.lowercase()
        ?.let { isJwHost(it) } == true

/**
 * 是否为教务主页面：**host 必须在白名单内**且 path 精确等于 [MAIN_PAGE_MARK]。
 *
 * 此前是 `url.contains(MAIN_PAGE_MARK)`：任何域名下含该路径的 URL（含查询串伪造）
 * 都会触发抓取脚本注入，而抓取脚本会调用 @JavascriptInterface 桥向本地库写入数据。
 */
private fun isMainPageUrl(url: String): Boolean {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    if (!isJwHost(host)) return false
    return uri.path == MAIN_PAGE_MARK
}

/** 读取 assets 内的注入脚本文本。 */
fun loadAssetScript(context: android.content.Context, path: String): String =
    context.assets.open(path).bufferedReader().use { it.readText() }

/**
 * 清除教务登录会话（"退出教务登录"）。
 *
 * 为什么必须有这个入口：WebView 的统一身份认证 SESSION cookie 由 CookieManager 落盘在
 * `app_webview/`，按设计会一直保留（备份规则已排除它，所以不会随云备份/换机外流，但会
 * 长期留在本机）。此前全项目只有"清除成绩缓存"，没有任何终止会话的手段 —— 手机借人、
 * 二手机转卖时 WebView 仍是登录态。
 *
 * 清的是"登录态"，不是课表/成绩数据（那是本地 Room/DataStore，由"清除成绩缓存"负责）。
 */
fun clearJwSession(context: android.content.Context) {
    runCatching {
        android.webkit.CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }
    runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
    runCatching {
        android.webkit.WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    }
}
