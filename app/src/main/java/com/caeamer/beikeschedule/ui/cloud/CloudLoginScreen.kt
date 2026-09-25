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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/** 等待 WebView 就绪的上限（授权拿到后 WebView 可能正在重建）。 */
private const val AWAIT_WEBVIEW_TIMEOUT_MS = 1_500L

/** 授权回调跳转后的看门狗时长（秒），见 CloudLoginScreen 内注释。 */
private const val AUTH_NAV_WATCHDOG_SECONDS = 15

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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val qrSid by viewModel.qrSid.collectAsStateWithLifecycle()
    val qrHint by viewModel.qrHint.collectAsStateWithLifecycle()
    val authorizedUrl by viewModel.authorizedUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pageLoading by remember { mutableStateOf(true) }

    // 换 token 关键请求中吞掉返回：token 在后台跑完会落盘，放行返回会让界面状态
    // 与实际登录态脱节（ImportScreen 的 Committing 态同理）
    BackHandler(enabled = state !is CloudLoginUiState.Done && state !is CloudLoginUiState.SigningIn) {
        onDone()
    }

    LaunchedEffect(state) {
        if (state is CloudLoginUiState.Done) onDone()
    }

    // 微信授权到达（App 自己轮询到的，不依赖学校页面的轮询）：把主框架推到 SSO 回调。
    // 之后 SSO 种下会话并跳回教务系统主页 → onMainPage → 自动抓学号。
    // 勿打完整 URL：含 auth_code/rand_token，release 也会进 logcat。
    // WebView 可能尚未创建/正在重建：稍等再 load，避免授权成功却丢了跳转。
    // authCode 是一次性的：重试耗尽仍没有 WebView 时必须报错并复位，
    // 否则用户在微信侧点过授权却毫无反应，只能干等。
    LaunchedEffect(authorizedUrl) {
        val url = authorizedUrl ?: return@LaunchedEffect
        val target = kotlinx.coroutines.withTimeoutOrNull(AWAIT_WEBVIEW_TIMEOUT_MS) {
            while (webView == null) kotlinx.coroutines.delay(50)
            webView
        }
        if (target == null) return@LaunchedEffect viewModel.onAuthorizedNavigationLost()

        val before = target.url
        target.loadUrl(url)

        // 看门狗：这次跳转被 WebView 静默丢弃时（典型是某个重定向被导航策略拒掉——SSO 的
        // 回调 302 曾指向 http://sso.ustb.edu.cn/...），页面不换、onReceivedError 也不回调，
        // 界面就会永久停在"授权成功，正在进入教务系统…"。主框架 URL 变了才算真的在走，
        // 长时间不变就复位授权地址并让微认证页重新出码，用户可以直接重扫，不必杀进程。
        repeat(AUTH_NAV_WATCHDOG_SECONDS) {
            kotlinx.coroutines.delay(1_000)
            val now = webView?.url
            if (now != null && now != before) return@LaunchedEffect
        }
        viewModel.onAuthorizedNavigationLost()
        webView?.reload()
    }

    val showingWebView = state !is CloudLoginUiState.Done
    LaunchedEffect(showingWebView) { onLightBackgroundVisible(showingWebView) }
    DisposableEffect(Unit) { onDispose { onLightBackgroundVisible(false) } }

    // 抓学号（Browsing 手动重试也走这里）。
    // 脚本内是相对路径 /user/me：必须在 byyt 主框架上注入，否则会打到 SSO/sis 域得到 404。
    val fetchIdentity: () -> Unit = {
        val wv = webView
        val pageUrl = wv?.url.orEmpty()
        when {
            wv == null -> viewModel.onError("页面尚未就绪，请稍候再试")
            !isByytHost(pageUrl) ->
                viewModel.onError("请先登录并进入教务系统主页后再获取（当前不在教务页）")
            else -> wv.evaluateJavascript(loadAssetScript(context, "import/jw_identity.js"), null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录云同步") },
                navigationIcon = {
                    // 换 token 期间禁用返回（与 BackHandler 同口径）
                    IconButton(onClick = onDone, enabled = state !is CloudLoginUiState.SigningIn) {
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
                                            val sid = qrSid.orEmpty()
                                            // 锁定该 sid：页面换码时继续轮询，微信里授权不会丢
                                            viewModel.pinSidForWeChat(sid)
                                            copyToClipboard(
                                                context,
                                                QrAuthApi.wechatAuthorizeUrl(sid),
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
                            onIdentity = { xh, xm -> webView?.post {
                                // 平台层桥按 ustb 任意子域放行（JwWebView.BRIDGE_ORIGINS）；
                                // 身份凭据只信 byyt 本体页当前主框架回传的，其余子域
                                // （含潜在被 XSS 的页面）一律拒绝——否则伪造学号会把
                                // 受害者的备份写进攻击者账号
                                if (isByytHost(webView?.url)) viewModel.onIdentity(xh, xm)
                            } },
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

/** 当前页是否为教务本体域（byyt），身份脚本只能在这里跑。 */
internal fun isByytHost(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
    return host == "byyt.ustb.edu.cn"
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
