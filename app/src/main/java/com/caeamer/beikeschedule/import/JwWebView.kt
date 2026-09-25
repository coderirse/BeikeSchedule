package com.caeamer.beikeschedule.import

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.WebViewCompat
import com.caeamer.beikeschedule.BuildConfig

private const val JW_HOME = "https://byyt.ustb.edu.cn"

/**
 * 统一认证入口：教务登录页那颗"统一身份认证登录"按钮跳的就是这个地址。
 *
 * 一键同步页用它作为 WebView 起始地址——有教务会话时 SSO 会立刻带 code 跳回教务主页
 * （用户看不到任何登录界面），没有会话时直接进 SSO 移动版扫码页，省掉"先看登录页再手点"。
 */
internal const val JW_SSO_ENTRY_URL = "$JW_HOME/oauth/login/code"
private const val MAIN_PAGE_MARK = "/authentication/main"
private const val TAG = "BeikeJwWebView"

/**
 * JS 桥允许注入的 origin 规则。
 *
 * 收口目标是"只有教务系统自己的页面能调用桥"：
 * - 教务站点本体（脚本实际注入的页面）；
 * - ustb.edu.cn 其它子域（统一身份认证可能在别的子域，登录页上手动抓取失败时
 *   仍需要把错误经桥报回界面，否则用户看到的是"点了没反应"）。
 *
 * 第三方 iframe（非 ustb 域）拿不到桥对象；回调里再要求主框架，双保险。
 */
private val BRIDGE_ORIGINS = setOf("https://byyt.ustb.edu.cn", "https://*.ustb.edu.cn")

/**
 * 教务页面渲染修正脚本，解决 WebView 白页：
 * 1. 页面 rem 适配按 1920px 桌面设计（fontSize = clientWidth/1920*37.5），
 *    而其 meta viewport 是 width=device-width → 手机上布局宽 360px、根字体仅 7px，
 *    须改写为 width=1440 恢复桌面比例（useWideViewPort 会被 meta 覆盖，只能注入改写）；
 * 2. .page{height:100vh} 在此 WebView 中 vh/百分比高度均算出 0（ICB 高度异常），
 *    导致 #app 高度 0 且 overflow:hidden 裁掉全部内容，须用 innerHeight 像素值补上。
 * 页面脚本监听视口变化会自动重算 rem，注入后无需刷新。脚本幂等，每次导航重复注入。
 *
 * **为什么不能一进来就置 `__bkPageFixInstalled`**：本脚本会在 onPageStarted 注入，
 * 此时文档常常还没有根元素（document.head 与 document.documentElement 同时为 null），
 * 旧写法第 3 行先置标志、第 10 行才 appendChild → 抛 TypeError 中断，而后续
 * onPageFinished/500ms/1500ms 三次兜底注入全被标志挡掉，修复一次都没生效：
 * meta 仍是 width=device-width → 本页面 .page{height:100vh} 在此 WebView 算出 0
 * （实测 vhProbe=0）+ overflow:hidden → 整页裁空成白屏（导入页/成绩页/云登录页通吃）。
 * 该失败取决于注入时机（实测连续三次导航里挂一次），所以表现为"之前能加载、现在不行"
 * 这种时好时坏。现在改为：文档根就绪才算装上，没就绪则条件重试。
 */
internal const val PAGE_FIX_JS = """
(function () {
  if (window.__bkPageFixInstalled) return;
  function root() { return document.head || document.documentElement; }
  function fixAll() {
    var r = root();
    // 文档根还没建出来：返回 false 交给条件重试，绝不能在这里把"已安装"标志置上
    if (!r) return false;
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) {
      m = document.createElement('meta');
      m.name = 'viewport';
      r.appendChild(m);
    }
    if (m.getAttribute('content') !== 'width=1440') m.setAttribute('content', 'width=1440');
    var app = document.querySelector('#app');
    if (app && app.getBoundingClientRect().height === 0) {
      app.style.setProperty('height', window.innerHeight + 'px', 'important');
    }
    // 真正装上（含 MutationObserver）之后才置标志，否则一次失败会把兜底注入全锁死
    if (!window.__bkPageFixInstalled) {
      window.__bkPageFixInstalled = true;
      new MutationObserver(fixAll).observe(document.documentElement, { childList: true, subtree: true });
      document.addEventListener('DOMContentLoaded', fixAll);
      setTimeout(fixAll, 500);
      setTimeout(fixAll, 1500);
    }
    return true;
  }
  if (!fixAll()) {
    // 注入早于文档根：轮询等它出现（60 × 50ms ≈ 3s，覆盖慢网络下的首字节延迟）
    var tries = 0;
    var timer = setInterval(function () {
      if (fixAll() || ++tries > 60) clearInterval(timer);
    }, 50);
  }
})();
"""

/**
 * 教务系统 WebView（导入页/成绩页共用）：
 * 登录统一认证 → 到达主页后回调 onMainPage（由调用方注入抓取脚本）。
 *
 * JS 桥（[bridge]/[bridgeName]）走 `WebViewCompat.addWebMessageListener`：
 * 对象只注入给下面的 [BRIDGE_ORIGINS] 列出的 origin，且回调里再校验"主框架 + 教务域名"。
 * 此前用 `addJavascriptInterface`，它对 WebView 里**每个 frame** 生效，
 * 白名单页面内嵌的第三方 iframe 能直接调用桥伪造数据（导航白名单管不到 iframe 与重定向）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwWebView(
    /**
     * 具名桥列表（桥名 ↔ 桥实现）。一键同步页同时注册三个脚本的桥：
     * `BeikeImport` / `BeikeGrades` / `BeikeIdentity`，每个桥自带失败回调，
     * 脚本报错因此能落到具体步骤上。
     */
    bridges: List<Pair<String, JwBridge>>,
    onMainPage: () -> Unit,
    /** 起始地址；一键同步页传 [JW_SSO_ENTRY_URL] 直达统一认证，其余场景保持教务首页。 */
    startUrl: String = JW_HOME,
    onCreated: (WebView) -> Unit = {},
    onPageStarted: () -> Unit = {},
    onPageError: (String) -> Unit = {},
    onPageProgress: (Int) -> Unit = {},
    /**
     * 每次子资源请求回调（含 iframe 内请求）。
     * 云登录页用它监听贝壳教学平台微认证 iframe 的 qrpage/qrimg 请求，
     * 原生取到二维码 sid（不依赖注入脚本与页面自身轮询，见 CloudLoginViewModel）。
     */
    onSubresourceRequest: ((android.webkit.WebResourceRequest) -> Unit)? = null,
    /**
     * document-start 注入脚本（按 origin 规则，**含 iframe**）：用于在页面脚本运行前
     * 改写其行为。云登录页用它禁用微认证页自带的二维码轮询（该轮询与 App 原生轮询
     * 会互相触发 205 并发冲突，页面把 205 当"二维码已失效"处理并停止轮询）。
     */
    documentStartScripts: List<Pair<Set<String>, String>> = emptyList(),
) {
    val context = LocalContext.current
    // document-start 脚本句柄：离开组合时要移除，否则 WebView 复用时脚本重复注入
    val scriptHandlers = remember { mutableListOf<androidx.webkit.ScriptHandler>() }
    DisposableEffect(documentStartScripts) {
        onDispose {
            scriptHandlers.forEach { runCatching { it.remove() } }
            scriptHandlers.clear()
        }
    }
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
                // 桥：平台按 origin 限定可见范围（见类注释），回调里再校验主框架 + 教务域名。
                // 两条 origin 规则覆盖"教务站点本体"与"统一认证可能用到的其它 ustb 子域"，
                // 保证登录页上手动抓取失败时仍能把错误经桥报回界面（而不是静默无反应）。
                bridges.forEach { (name, bridge) ->
                    WebViewCompat.addWebMessageListener(
                        this,
                        name,
                        BRIDGE_ORIGINS,
                    ) { _, message, sourceOrigin, isMainFrame, _ ->
                        if (!isMainFrame) return@addWebMessageListener
                        val host = sourceOrigin.host?.lowercase() ?: return@addWebMessageListener
                        if (!isJwHost(host)) return@addWebMessageListener
                        message.data?.let(bridge::dispatch)
                    }
                }
                // document-start 脚本：平台保证在匹配 origin 的每个 frame（含 iframe）
                // 的页面脚本之前执行；句柄记下来供离开组合时移除
                documentStartScripts.forEach { (origins, script) ->
                    runCatching {
                        scriptHandlers += WebViewCompat.addDocumentStartJavaScript(this, script, origins)
                    }
                }
                webViewClient = object : WebViewClient() {
                    // 域名白名单：站外链接一律转交系统浏览器，避免把教务会话带进任意站点；
                    // 站内（ustb.edu.cn 及其子域）的 http 与 https **同等放行**（见 jwNavPolicy）。
                    // （桥的可见范围另有平台级 origin 限定，见 BRIDGE_ORIGINS。）
                    //
                    // **必须失败关闭**：host 为 null 的 file:/content:/data:/blob: 与
                    // 非 http(s) 的 scheme（weixin:/intent: 等）继续拒绝且不交接——
                    // 这条与"支持 http"无关，是防任意本地/外部 scheme 进 WebView 的底线。
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        val host = url.host?.lowercase()
                        val policy = jwNavPolicy(url.scheme, host)
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(TAG, "nav ${url.scheme}://$host -> $policy")
                        }
                        return when (policy) {
                            // 教务/统一认证站内（http 或 https）：WebView 自己加载
                            JwNavPolicy.ALLOW_IN_WEBVIEW -> false
                            // 站外 http/https：交系统浏览器，避免把教务会话带进任意站点
                            JwNavPolicy.EXTERNAL_BROWSER -> runCatching {
                                view.context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, url),
                                )
                                true
                            }.getOrDefault(true)
                            // 非 http(s) / 无 host：拒绝加载，也不交接
                            JwNavPolicy.BLOCK -> true
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        onPageStarted()
                        // 尽早注入，MutationObserver 会在 meta 标签解析出来时立即改写。
                        // 只对教务本体域注入：SSO 的 ac-h5 是移动版页面，改写 viewport 反而会把
                        // 二维码等元素缩成小图；ac-h5 自带移动适配，不需要也不应被修正。
                        if (isByytUrl(url)) view.evaluateJavascript(PAGE_FIX_JS, null)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // 兜底注入（脚本幂等），覆盖 onPageStarted 时机过晚的情况
                        if (isByytUrl(url)) view.evaluateJavascript(PAGE_FIX_JS, null)
                        // 主页面判定改为 host + path **精确**匹配：
                        // 此前是 url.contains("/authentication/main")，任意域名下含该路径的
                        // URL（如 https://evil.example/authentication/main）都会触发抓取脚本注入。
                        if (isMainPageUrl(url)) {
                            view.post { onMainPage() }
                        }
                    }

                    /** 子资源请求（含 iframe）：云登录页据此原生捕获微认证二维码 sid。 */
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                        onSubresourceRequest?.invoke(request)
                        return null
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
                loadUrl(startUrl)
                onCreated(this)
            }
        },
        // WebView 必须显式销毁：AndroidView 离开组合时若只丢掉引用，持有 Activity context 的
        // WebView 与其 JS 定时器（PAGE_FIX_JS 里的 MutationObserver / setTimeout）会一起泄漏。
        // 导入页的"预览 → 重新抓取"会反复创建新实例，成绩页每次抓取完成后也会销毁一个。
        onRelease = { view ->
            bridges.forEach { (name, _) ->
                runCatching { WebViewCompat.removeWebMessageListener(view, name) }
            }
            runCatching { view.stopLoading() }
            runCatching { view.loadUrl("about:blank") }
            runCatching { view.destroy() }
        },
    )
}

/** 单桥便捷重载：保持原有调用方式（桥名 + 桥实现）。 */
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
    onSubresourceRequest: ((android.webkit.WebResourceRequest) -> Unit)? = null,
    documentStartScripts: List<Pair<Set<String>, String>> = emptyList(),
) = JwWebView(
    bridges = listOf(bridgeName to bridge),
    onMainPage = onMainPage,
    startUrl = JW_HOME,
    onCreated = onCreated,
    onPageStarted = onPageStarted,
    onPageError = onPageError,
    onPageProgress = onPageProgress,
    onSubresourceRequest = onSubresourceRequest,
    documentStartScripts = documentStartScripts,
)

/** 教务系统域名白名单：ustb.edu.cn 及其子域。前导点保证 evilustb.edu.cn 不匹配。 */
internal fun isJwHost(host: String): Boolean =
    host == "ustb.edu.cn" || host.endsWith(".ustb.edu.cn")

/**
 * 当前页是否为教务本体域（byyt）——三个抓取脚本只允许在这里运行。
 *
 * 脚本内全是相对路径（`/user/me`、`/xszykb/...`、`/cjgl/...`），注入到 SSO 或微认证域
 * 会打到错误站点拿到 404；此外平台层桥按 ustb 任意子域放行，所以"主框架 + byyt 域"
 * 是脚本结果可信的必要条件（否则被 XSS 的子域可伪造抓取结果）。
 */
internal fun isByytHost(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
    return host == "byyt.ustb.edu.cn"
}

/** [jwNavPolicy] 的判定结果。 */
internal enum class JwNavPolicy {
    /** 教务/统一认证站内 http 或 https：放行给 WebView。 */
    ALLOW_IN_WEBVIEW,

    /** 站外 http/https：转交系统浏览器。 */
    EXTERNAL_BROWSER,

    /** 无 host 或非 http(s) scheme：拒绝加载且不交接。 */
    BLOCK,
}

/**
 * 导航策略（纯逻辑，JVM 可单测）。
 *
 * 站内按 host 白名单收口，**http 与 https 同等放行**：学校部分服务/跳转只有 http
 * （微认证授权成功后 SSO 的回调 302 就指向 http://sso.ustb.edu.cn/idp/thirdAuth/...），
 * 一律拒绝 https 之外的 scheme 会把这类跳转静默丢掉，表现为云登录永久卡在"授权成功"。
 * 代价是站内明文可被中间人窃听/篡改，因此只对 ustb.edu.cn 子域放开；
 * 站外仍然只交系统浏览器，无 host / 非 http(s) 一律不加载。
 */
internal fun jwNavPolicy(scheme: String?, host: String?): JwNavPolicy {
    val h = host?.lowercase()
    val jw = h != null && isJwHost(h)
    return when {
        jw && isHttpScheme(scheme) -> JwNavPolicy.ALLOW_IN_WEBVIEW
        h != null && isHttpScheme(scheme) -> JwNavPolicy.EXTERNAL_BROWSER
        else -> JwNavPolicy.BLOCK
    }
}

/** 站内页面允许的 scheme：http 与 https 等价（学校部分服务只有 http）。 */
internal fun isHttpScheme(scheme: String?): Boolean = scheme == "http" || scheme == "https"

/** 教务系统本体域（页面缩放修正 PAGE_FIX_JS 只对它注入，见 JwWebView 注释）。 */
private fun isByytUrl(url: String): Boolean =
    runCatching { android.net.Uri.parse(url) }.getOrNull()
        ?.takeIf { isHttpScheme(it.scheme) }
        ?.host?.lowercase() == "byyt.ustb.edu.cn"

/**
 * 是否为教务主页面：**host 必须在白名单内**且 path 精确等于 [MAIN_PAGE_MARK]。
 *
 * 此前是 `url.contains(MAIN_PAGE_MARK)`：任何域名下含该路径的 URL（含查询串伪造）
 * 都会触发抓取脚本注入，而抓取脚本会调用 @JavascriptInterface 桥向本地库写入数据。
 */
private fun isMainPageUrl(url: String): Boolean {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
    if (!isHttpScheme(uri.scheme)) return false
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
