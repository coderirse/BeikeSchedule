package com.caeamer.beikeschedule.ui.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TextButton
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
import com.caeamer.beikeschedule.data.remote.QrAuthApi
import com.caeamer.beikeschedule.import.CloudLoginBridge
import com.caeamer.beikeschedule.import.GradesBridge
import com.caeamer.beikeschedule.import.JwImportBridge
import com.caeamer.beikeschedule.import.JwWebView
import com.caeamer.beikeschedule.import.JW_SSO_ENTRY_URL
import com.caeamer.beikeschedule.import.isByytHost
import com.caeamer.beikeschedule.import.loadAssetScript
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 等待 WebView 就绪的上限（授权拿到后 WebView 可能正在重建）。 */
private const val AWAIT_WEBVIEW_TIMEOUT_MS = 1_500L

/** 授权回调跳转后的看门狗时长（秒）。 */
private const val AUTH_NAV_WATCHDOG_SECONDS = 15

/**
 * 一键同步：一次扫码跑完 课表导入 + 成绩抓取 + 云账号登录（必要时上传备份）。
 *
 * 与旧的三套页面（导入 / 成绩 / 云登录）相比，登录只在**这里**发生一次；
 * 教务会话还在时进入本页不会看到任何登录界面，直接跑完给汇总。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSyncScreen(
    onDone: () -> Unit,
    onLightBackgroundVisible: (Boolean) -> Unit = {},
    viewModel: UnifiedSyncViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val qrSid by viewModel.qrSid.collectAsStateWithLifecycle()
    val qrHint by viewModel.qrHint.collectAsStateWithLifecycle()
    val authorizedUrl by viewModel.authorizedUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoading by remember { mutableStateOf(true) }

    BackHandler { onDone() }
    LaunchedEffect(state) { onLightBackgroundVisible(true) }
    // 同一进程内再次进入本页时，清掉上一次流程留下的终态（否则会直接看到旧汇总）
    LaunchedEffect(Unit) { viewModel.resetIfFinished() }
    DisposableEffect(Unit) {
        onDispose {
            // 离开即取消：写库是单事务、上传/恢复各自带锁，取消不会留下半截数据
            viewModel.cancel()
            onLightBackgroundVisible(false)
        }
    }

    // 授权到达：把主框架推到 SSO 回调（之后 SSO 种会话并跳回教务主页 → onPageReady）
    LaunchedEffect(authorizedUrl) {
        val url = authorizedUrl ?: return@LaunchedEffect
        val target = withTimeoutOrNull(AWAIT_WEBVIEW_TIMEOUT_MS) {
            while (webView == null) delay(50)
            webView
        }
        if (target == null) return@LaunchedEffect viewModel.onAuthorizedNavigationLost()

        val before = target.url
        target.loadUrl(url)
        // 看门狗：跳转被静默丢弃时页面不换、onReceivedError 也不回调，界面会永久停在
        // "授权成功…"。主框架 URL 变了才算真的在走，长时间不变就复位授权地址并重出码。
        repeat(AUTH_NAV_WATCHDOG_SECONDS) {
            delay(1_000)
            val now = webView?.url
            if (now != null && now != before) return@LaunchedEffect
        }
        viewModel.onAuthorizedNavigationLost()
        webView?.reload()
    }

    // 需要执行的脚本（由 ViewModel 按步骤顺序发出）
    LaunchedEffect(Unit) {
        viewModel.jsRequests.collect { asset ->
            val step = (viewModel.state.value as? UnifiedSyncUiState.Running)?.current
            val target = withTimeoutOrNull(AWAIT_WEBVIEW_TIMEOUT_MS) {
                while (webView == null) delay(50)
                webView
            }
            if (target == null) {
                step?.let { viewModel.onScriptError(it, "页面尚未就绪，请稍后重试") }
                return@collect
            }
            if (!isByytHost(target.url)) {
                step?.let { viewModel.onScriptError(it, "请先登录并进入教务系统主页（当前不在教务页）") }
                return@collect
            }
            target.evaluateJavascript(loadAssetScript(context, asset), null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录并同步") },
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                bannerText(state, qrHint),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state is UnifiedSyncUiState.Failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.weight(1f),
                            )
                            when (state) {
                                is UnifiedSyncUiState.Login -> Row {
                                    if (qrSid != null) {
                                        OutlinedButton(onClick = {
                                            val sid = qrSid.orEmpty()
                                            viewModel.pinSidForWeChat(sid)
                                            copyToClipboard(context, QrAuthApi.wechatAuthorizeUrl(sid))
                                        }) { Text("复制授权链接") }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = { viewModel.manualRestart() }) { Text("手动获取") }
                                }
                                is UnifiedSyncUiState.Failed -> OutlinedButton(onClick = {
                                    viewModel.resetToLogin()
                                    webView?.reload()
                                }) { Text("重试") }
                                else -> Unit
                            }
                        }
                    }
                }
                if (pageLoading || state is UnifiedSyncUiState.Running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                JwWebView(
                    bridges = listOf(
                        "BeikeIdentity" to CloudLoginBridge(
                            onIdentity = { xh, xm -> webView?.post {
                                // 身份凭据只信 byyt 本体页主框架回传的
                                if (isByytHost(webView?.url)) viewModel.onIdentity(xh, xm)
                            } },
                            onFailure = { msg -> webView?.post { viewModel.onScriptError(SyncStep.IDENTITY, msg) } },
                        ),
                        "BeikeImport" to JwImportBridge(
                            onSuccess = { semester, published, courses, sections, weekDates, calendar ->
                                webView?.post {
                                    if (isByytHost(webView?.url)) {
                                        viewModel.onImportResult(semester, published, courses, sections, weekDates, calendar)
                                    }
                                }
                            },
                            onFailure = { msg -> webView?.post { viewModel.onScriptError(SyncStep.TIMETABLE, msg) } },
                        ),
                        "BeikeGrades" to GradesBridge(
                            onResult = { gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk ->
                                webView?.post {
                                    if (isByytHost(webView?.url)) {
                                        viewModel.onGradesResult(gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk)
                                    }
                                }
                            },
                            onFailure = { msg -> webView?.post { viewModel.onScriptError(SyncStep.GRADES, msg) } },
                        ),
                    ),
                    startUrl = JW_SSO_ENTRY_URL,
                    onMainPage = { viewModel.onPageReady() },
                    onCreated = { webView = it },
                    onPageStarted = { pageLoading = true },
                    onPageError = { msg -> pageLoading = false; viewModel.onPageError(msg) },
                    onPageProgress = { p -> if (p >= 100) pageLoading = false },
                    onSubresourceRequest = { req -> viewModel.onSubresource(req.url.toString()) },
                    documentStartScripts = listOf(
                        setOf("https://sis.ustb.edu.cn") to QR_POLL_SUPPRESS_JS,
                    ),
                )
            }

            when (val s = state) {
                is UnifiedSyncUiState.Running -> SyncProgressCard(s)
                is UnifiedSyncUiState.Done -> SyncSummaryCard(
                    results = s.results,
                    note = s.note,
                    onRetry = { viewModel.retryFailed() },
                    onFinish = onDone,
                )
                else -> Unit
            }
        }
    }

    when (val s = state) {
        is UnifiedSyncUiState.NeedDecision -> when (s.kind) {
            DecisionKind.ACCOUNT_SWITCH -> AlertDialog(
                onDismissRequest = { viewModel.answerAccountSwitch(false) },
                title = { Text(s.title) },
                text = { Text(s.text) },
                confirmButton = {
                    TextButton(onClick = { viewModel.answerAccountSwitch(true) }) { Text("切换云账号") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.answerAccountSwitch(false) }) { Text("取消") }
                },
            )

            DecisionKind.CLOUD_MODE -> AlertDialog(
                onDismissRequest = { viewModel.answerCloudMode(CloudMode.NO_UPLOAD) },
                title = { Text(s.title) },
                text = { Text(s.text) },
                confirmButton = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { viewModel.answerCloudMode(CloudMode.RESTORE) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("从云端恢复") }
                        Button(
                            onClick = { viewModel.answerCloudMode(CloudMode.UPLOAD) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("用本机覆盖云端") }
                        OutlinedButton(
                            onClick = { viewModel.answerCloudMode(CloudMode.NO_UPLOAD) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("暂不上传") }
                    }
                },
                dismissButton = null,
            )
        }
        else -> Unit
    }
}

private fun bannerText(state: UnifiedSyncUiState, qrHint: String?): String = when (state) {
    is UnifiedSyncUiState.Login ->
        qrHint ?: "登录北科大统一身份认证：用另一台设备的微信扫码，或同一台手机复制授权链接到微信打开"
    is UnifiedSyncUiState.Running -> "正在${state.current.label}…"
    is UnifiedSyncUiState.NeedDecision -> state.title
    is UnifiedSyncUiState.Done -> "同步完成"
    is UnifiedSyncUiState.Failed -> state.message
}

/** 执行中：已完成步骤 + 当前步骤的进度卡片。 */
@Composable
private fun SyncProgressCard(state: UnifiedSyncUiState.Running) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("正在同步", style = MaterialTheme.typography.titleMedium)
            state.results.forEach { StepRow(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("${state.current.label}…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 结束：逐项 ✓/✗ 汇总 + 失败项重试。 */
@Composable
private fun SyncSummaryCard(
    results: List<SyncStepResult>,
    note: String?,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
) {
    val failed = results.count { it.status == SyncStatus.FAILED }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (failed == 0) "同步完成" else "同步完成（$failed 项失败）",
                style = MaterialTheme.typography.titleMedium,
            )
            results.forEach { StepRow(it) }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed > 0) {
                    OutlinedButton(onClick = onRetry) { Text("重试失败项") }
                }
                Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("完成") }
            }
        }
    }
}

@Composable
private fun StepRow(result: SyncStepResult) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when (result.status) {
            SyncStatus.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
            SyncStatus.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
            SyncStatus.SKIPPED -> Icons.Default.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(result.step.label, style = MaterialTheme.typography.bodyMedium)
        result.message?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.status == SyncStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 复制微信授权链接（同机扫码的替代入口）。 */
private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("贝壳课表授权链接", text))
    }
    Toast.makeText(context, "已复制，去微信粘贴打开并确认授权", Toast.LENGTH_LONG).show()
}

/**
 * 禁用微认证二维码页自带的状态轮询（sis 域所有 frame 的 document-start 注入）。
 * 页面轮询在慢网下会 30 秒超时 abort → "失败即 reload" 换码；且与 App 原生轮询并发时互吃 205。
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
