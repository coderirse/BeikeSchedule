package com.example.beikeschedule.import

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

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

/** 教务导入页：WebView 登录 → 自动注入脚本抓取 → 预览确认入库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onDone: () -> Unit, viewModel: ImportViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pageLoading by remember { mutableStateOf(true) }

    val runScript: () -> Unit = {
        val wv = webView
        if (wv != null) {
            viewModel.onFetchStart()
            val js = context.assets.open("import/jw_import.js").bufferedReader().use { it.readText() }
            wv.evaluateJavascript(js, null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("从教务系统导入") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is ImportUiState.Browsing, is ImportUiState.Fetching -> {
                    Column(Modifier.fillMaxSize()) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    pageError ?: "登录教务系统后将自动获取课表",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (pageError != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(onClick = runScript) { Text("手动抓取") }
                            }
                        }
                        if (state is ImportUiState.Fetching || pageLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        JwWebView(
                            onCreated = { webView = it },
                            onMainPage = runScript,
                            onResult = { sem, pub, zong, kb, rl, cal ->
                                viewModel.onFetchResult(sem, pub, zong, kb, rl, cal)
                            },
                            onError = { viewModel.onFetchError(it) },
                            onPageError = { pageError = it },
                            onPageProgress = { pageLoading = it < 100 },
                            onPageStarted = { pageError = null },
                        )
                    }
                }

                is ImportUiState.Preview -> ImportPreview(
                    preview = s,
                    onConfirm = { viewModel.confirmImport(onDone) },
                    onBack = { viewModel.backToBrowsing() },
                )

                is ImportUiState.Error -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("导入失败", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { viewModel.backToBrowsing() }) { Text("返回重试") }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun JwWebView(
    onCreated: (WebView) -> Unit,
    onMainPage: () -> Unit,
    onResult: (String, String, String, String, String, String) -> Unit,
    onError: (String) -> Unit,
    onPageError: (String) -> Unit,
    onPageProgress: (Int) -> Unit,
    onPageStarted: () -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            // 仅调试包允许 DevTools 远程调试 WebView
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 配合 PAGE_FIX_JS 的 meta 改写：宽视口布局 + 总览缩放把 PC 页面缩放到一屏
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(
                    JwImportBridge(
                        onSuccess = { sem, pub, zong, kb, rl, cal ->
                            post { onResult(sem, pub, zong, kb, rl, cal) }
                        },
                        onFailure = { msg -> post { onError(msg) } },
                    ),
                    "BeikeImport",
                )
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

@Composable
private fun ImportPreview(
    preview: ImportUiState.Preview,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("抓取成功", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("学期：${preview.semesterName}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "课程：${preview.scheduledCount} 门" +
                if (preview.unscheduledCount > 0) "（另有无固定时间课程 ${preview.unscheduledCount} 门）" else "",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "开学日期：${preview.firstMonday.ifBlank { "未识别，请导入后在设置中填写" }}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            if (preview.weekMondays.isNotEmpty()) {
                "教学周日历：已获取（共 ${preview.weekMondays.size} 周，含官方放假跳周）"
            } else {
                "教学周日历：未获取，将按开学日期逐周推算"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "确认后将覆盖已有的教务导入数据，并清除示例课表。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row {
            OutlinedButton(onClick = onBack) { Text("重新抓取") }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("确认导入") }
        }
    }
}
