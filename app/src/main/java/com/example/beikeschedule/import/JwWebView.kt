package com.example.beikeschedule.import

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
    bridge: Any,
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
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(bridge, bridgeName)
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        onPageStarted()
                        // 尽早注入，MutationObserver 会在 meta 标签解析出来时立即改写
                        view.evaluateJavascript(PAGE_FIX_JS, null)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // 兜底注入（脚本幂等），覆盖 onPageStarted 时机过晚的情况
                        view.evaluateJavascript(PAGE_FIX_JS, null)
                        if (url.contains(MAIN_PAGE_MARK)) {
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
    )
}

/** 读取 assets 内的注入脚本文本。 */
fun loadAssetScript(context: android.content.Context, path: String): String =
    context.assets.open(path).bufferedReader().use { it.readText() }
