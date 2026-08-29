package com.example.beikeschedule.ui.grades

import android.webkit.WebView
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.beikeschedule.data.local.GradeEntity
import com.example.beikeschedule.import.GradesBridge
import com.example.beikeschedule.import.JwWebView
import com.example.beikeschedule.import.loadAssetScript
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 教务 Tab：成绩 GPA 页（缓存展示 + WebView 抓取刷新）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(viewModel: GradesViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩") },
                actions = {
                    if (!state.showWebView) {
                        IconButton(onClick = { viewModel.startRefresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新成绩")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.showWebView) {
                WebViewFetch(
                    fetching = state.fetching,
                    onFetchStart = { viewModel.onFetchStart() },
                    onResult = { gpa, grades -> viewModel.onFetchResult(gpa, grades) },
                    onError = { viewModel.onFetchError(it) },
                )
            } else if (state.grades.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("还没有成绩数据", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "登录教务系统即可自动获取成绩与 GPA",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (state.error != null) {
                        Text(
                            state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Button(onClick = { viewModel.startRefresh() }) { Text("去获取") }
                }
            } else {
                GradesContent(
                    state = state,
                    onErrorDismiss = { viewModel.dismissError() },
                )
            }
        }
    }
}

/** WebView 登录 + 自动抓取（与导入页同款体验）。 */
@Composable
private fun WebViewFetch(
    fetching: Boolean,
    onFetchStart: () -> Unit,
    onResult: (String, String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val runScript: () -> Unit = {
        onFetchStart()
        webView?.evaluateJavascript(loadAssetScript(context, "import/jw_grades.js"), null)
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                pageError ?: "登录教务系统后将自动获取成绩",
                style = MaterialTheme.typography.bodySmall,
                color = if (pageError != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (fetching || pageLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        JwWebView(
            bridge = GradesBridge(
                onResult = { gpa, grades -> webView?.post { onResult(gpa, grades) } },
                onFailure = { msg -> webView?.post { onError(msg) } },
            ),
            bridgeName = "BeikeGrades",
            onMainPage = runScript,
            onCreated = { webView = it },
            onPageError = { pageError = it },
            onPageProgress = { pageLoading = it < 100 },
        )
    }
}

@Composable
private fun GradesContent(state: GradesUiState, onErrorDismiss: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { GpaCard(state) }
        state.grouped.forEach { (semester, grades) ->
            item(key = "header_$semester") {
                Text(
                    semester,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(grades.size, key = { grades[it].id }) { index ->
                GradeRow(grades[index])
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
        item {
            if (state.error != null) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onErrorDismiss) { Text("知道了") }
                }
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GpaCard(state: GradesUiState) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    state.gpa?.gpa?.toString() ?: "—",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "GPA",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    state.gpa?.let {
                        append("专业排名 ${it.rank}/${it.totalStudents}")
                        append(" · 已获学分 ${it.earnedCredits}")
                        append(" · 通过 ${it.passedCourses} 门")
                    } ?: append("GPA 信息待刷新获取")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.fetchedAt > 0) {
                    "更新于 " + Instant.ofEpochMilli(state.fetchedAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                } else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun GradeRow(grade: GradeEntity) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(grade.kcmc, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                listOf(grade.kcxz, grade.kclb, if (grade.bkcx.isNotBlank() && grade.bkcx != "正考") grade.bkcx else null)
                    .filterNotNull().filter { it.isNotBlank() }
                    .joinToString(" · ") + " · ${grade.xf}学分",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            grade.zzcj,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (grade.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
