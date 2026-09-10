package com.caeamer.beikeschedule.import

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.webkit.WebView

/** 教务导入页：WebView 登录 → 自动注入脚本抓取 → 预览确认入库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onDone: () -> Unit,
    /**
     * 通知宿主：当前是否正在展示"浅底"页面（WebView 里的教务页底色是浅色）。
     * 宿主据此决定状态栏图标明暗 —— 深色模式下属主页面用白图标，
     * 但导入页的教务页面本身是浅色，必须切成深色图标，否则图标看不清。
     */
    onLightBackgroundVisible: (Boolean) -> Unit = {},
    viewModel: ImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var pageLoading by remember { mutableStateOf(true) }

    // 预览/错误态返回 → 回到 WebView 重新抓取；浏览/抓取态返回 → 退出整个导入流程。
    // 本页是 Launcher Activity 里的一个 composable 分支（不是独立 Activity），
    // 不拦返回键的话系统返回会直接 finish 掉 Activity，登录会话与预览一起丢。
    BackHandler {
        when (state) {
            is ImportUiState.Preview, is ImportUiState.Error -> viewModel.backToBrowsing()
            else -> onDone()
        }
    }

    // WebView 只在 Browsing/Fetching 两个分支里存在，也只有在这些分支下底色才是浅的
    val showingWebView = state is ImportUiState.Browsing || state is ImportUiState.Fetching
    LaunchedEffect(showingWebView) { onLightBackgroundVisible(showingWebView) }
    DisposableEffect(Unit) { onDispose { onLightBackgroundVisible(false) } }

    val runScript: () -> Unit = {
        webView?.evaluateJavascript(loadAssetScript(context, "import/jw_import.js"), null)
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
                            bridge = JwImportBridge(
                                onSuccess = { sem, pub, zong, kb, rl, cal ->
                                    webView?.post {
                                        viewModel.onFetchResult(sem, pub, zong, kb, rl, cal)
                                    }
                                },
                                onFailure = { msg -> webView?.post { viewModel.onFetchError(msg) } },
                            ),
                            bridgeName = "BeikeImport",
                            onMainPage = runScript,
                            onCreated = { webView = it },
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
