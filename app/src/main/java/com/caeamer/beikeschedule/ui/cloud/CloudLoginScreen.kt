package com.caeamer.beikeschedule.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import com.caeamer.beikeschedule.data.remote.QrAuthApi
import com.caeamer.beikeschedule.import.CloudLoginBridge
import com.caeamer.beikeschedule.import.JwWebView
import com.caeamer.beikeschedule.import.loadAssetScript

/**
 * 云同步登录页：内嵌教务 WebView 完成统一身份认证（扫码或账密均可），
 * 到达教务主页后自动注入身份脚本抓学号 → 换云 token → 完成。
 *
 * 与导入页同一套骨架（JwWebView + 桥 + 状态栏明暗回调）；
 * 登录成功的教务会话留在 WebView 里，与"导入课表/抓成绩"共用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLoginScreen(
    onDone: () -> Unit,
    onLightBackgroundVisible: (Boolean) -> Unit = {},
    viewModel: CloudLoginViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val qrSid by viewModel.qrSid.collectAsState()
    val qrHint by viewModel.qrHint.collectAsState()
    val authorizedUrl by viewModel.authorizedUrl.collectAsState()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pageLoading by remember { mutableStateOf(true) }

    BackHandler(enabled = state !is CloudLoginUiState.Done) { onDone() }

    LaunchedEffect(state) {
        if (state is CloudLoginUiState.Done) onDone()
    }

    // 微信授权到达（App 自己轮询到的，不依赖学校页面的轮询）：把主框架推到 SSO 回调。
    // 之后 SSO 种下会话并跳回教务系统主页 → onMainPage → 自动抓学号。
    // 勿打完整 URL：含 auth_code/rand_token，release 也会进 logcat。
    LaunchedEffect(authorizedUrl) {
        val url = authorizedUrl ?: return@LaunchedEffect
        webView?.loadUrl(url)
    }

    val showingWebView = state !is CloudLoginUiState.Done
    LaunchedEffect(showingWebView) { onLightBackgroundVisible(showingWebView) }
    DisposableEffect(Unit) { onDispose { onLightBackgroundVisible(false) } }

    // 抓学号（Browsing 手动重试也走这里）
    val fetchIdentity: () -> Unit = {
        val wv = webView
        if (wv == null) {
            viewModel.onError("页面尚未就绪，请稍候再试")
        } else {
            wv.evaluateJavascript(loadAssetScript(context, "import/jw_identity.js"), null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录云同步") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val banner = when (val s = state) {
                            is CloudLoginUiState.SigningIn -> "已获取学号 ${s.xh}，正在登录云账号…"
                            is CloudLoginUiState.Failed -> s.message
                            else -> pageError
                                ?: qrHint
                                ?: "登录北科大统一身份认证：用另一台设备的微信扫码，或同一台手机复制授权链接到微信打开"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                banner,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state is CloudLoginUiState.Failed || pageError != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.weight(1f),
                            )
                            when (state) {
                                is CloudLoginUiState.Failed -> OutlinedButton(onClick = {
                                    // 重试 = 重载页面：失败可能来自页面本身（加载中断）或换 token；
                                    // 只重跑脚本无法从"页面坏掉"里恢复。重载后若已登录会重新到达
                                    // 主页面并自动抓取；未登录则留在登录页由用户继续登录。
                                    viewModel.retry()
                                    webView?.reload()
                                }) { Text("重试") }
                                is CloudLoginUiState.Browsing -> Row {
                                    // 同机扫码入口：把当前二维码的 /scan/sync 链接送去微信打开，
                                    // 不再依赖"截图→相册扫码→回来页面已换码"的脆弱流程
                                    if (qrSid != null) {
                                        OutlinedButton(onClick = {
                                            copyToClipboard(
                                                context,
                                                QrAuthApi.wechatAuthorizeUrl(qrSid.orEmpty()),
                                            )
                                        }) { Text("复制授权链接") }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = fetchIdentity) {
                                        Text("手动获取")
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                if (state is CloudLoginUiState.SigningIn || pageLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (state is CloudLoginUiState.Browsing || state is CloudLoginUiState.Failed) {
                    JwWebView(
                        bridge = CloudLoginBridge(
                            onIdentity = { xh, xm -> webView?.post { viewModel.onIdentity(xh, xm) } },
                            onFailure = { msg -> webView?.post { viewModel.onError(msg) } },
                        ),
                        bridgeName = "BeikeIdentity",
                        onMainPage = { fetchIdentity() },
                        onCreated = { webView = it },
                        onPageStarted = { pageLoading = true; pageError = null },
                        onPageError = { msg -> pageError = msg; pageLoading = false },
                        onPageProgress = { p -> if (p >= 100) pageLoading = false },
                        // 原生捕获微认证二维码 sid：App 自己轮询授权状态，
                        // 不再依赖学校页面那个"失败即换码"的轮询（见 QrAuthApi 注释）
                        onSubresourceRequest = { req ->
                            viewModel.onSubresource(req.url.toString())
                        },
                        // 禁用微认证页自带轮询：它与 App 的原生轮询会互相触发 205
                        // 并发冲突，而页面把 205 当"二维码已失效"处理并停止轮询
                        documentStartScripts = listOf(
                            setOf("https://sis.ustb.edu.cn") to QR_POLL_SUPPRESS_JS,
                        ),
                    )
                }
            }

            // 换 token 期间盖一层进度：防止用户在关键请求中乱点
            if (state is CloudLoginUiState.SigningIn) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/** 复制微信授权链接到剪贴板（同机扫码的替代入口）。 */
private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("贝壳课表授权链接", text))
    }
    Toast.makeText(context, "已复制，去微信粘贴打开并确认授权", Toast.LENGTH_LONG).show()
}

/**
 * 禁用微认证二维码页自带的状态轮询（在 sis 域所有 frame 的 document-start 注入，
 * 先于页面脚本执行）。轮询改由 App 原生完成（[com.caeamer.beikeschedule.data.remote.QrAuthApi]）：
 * 页面轮询在慢网下会 30 秒超时 abort → 页面"失败即 reload"→ 二维码作废；
 * 且与 App 轮询并发时双方互吃 205，页面把 205 当失效处理。
 */
private const val QR_POLL_SUPPRESS_JS = """
(function () {
    if (window.__bkQrPollSuppressed) return;
    window.__bkQrPollSuppressed = true;
    var tries = 0;
    var timer = setInterval(function () {
        if (typeof window.getAuthState === 'function') {
            window.getAuthState = function () {};
            clearInterval(timer);
        } else if (++tries > 60) {
            clearInterval(timer);
        }
    }, 50);
})();
"""
