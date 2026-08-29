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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SCORE_FMT = DecimalFormat("0.00")

/** 教务 Tab：成绩 GPA 页（加权/GPA 双模式 + 学期筛选 + 课程勾选）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(viewModel: GradesViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

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
                    onResult = { gpa, grades, user -> viewModel.onFetchResult(gpa, grades, user) },
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
                    onModeChange = { viewModel.setScoreMode(it) },
                    onSemesterFilter = { viewModel.setSemesterFilter(it) },
                    onToggleCourse = { viewModel.toggleExcluded(it) },
                    onErrorDismiss = { viewModel.dismissError() },
                )
            }
        }
    }
}

/** WebView 登录 + 自动抓取。 */
@Composable
private fun WebViewFetch(
    fetching: Boolean,
    onFetchStart: () -> Unit,
    onResult: (String, String, String) -> Unit,
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
                onResult = { gpa, grades, user -> webView?.post { onResult(gpa, grades, user) } },
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
private fun GradesContent(
    state: GradesUiState,
    onModeChange: (ScoreMode) -> Unit,
    onSemesterFilter: (String) -> Unit,
    onToggleCourse: (String) -> Unit,
    onErrorDismiss: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScoreCard(state, onModeChange, onSemesterFilter, onToggleCourse) }
        state.grouped.forEach { (semester, grades) ->
            item(key = "header_$semester") {
                Text(
                    semester,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(grades, key = { it.id }) { grade ->
                GradeRow(grade)
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
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

/** GPA/加权成绩卡片：模式切换 + 学期筛选 + 课程勾选。 */
@Composable
private fun ScoreCard(
    state: GradesUiState,
    onModeChange: (ScoreMode) -> Unit,
    onSemesterFilter: (String) -> Unit,
    onToggleCourse: (String) -> Unit,
) {
    var showCourseSelector by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.scoreMode == ScoreMode.WEIGHTED) "加权成绩" else "GPA",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                // 模式切换小按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "加权",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.scoreMode == ScoreMode.WEIGHTED) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                    )
                    Switch(
                        checked = state.scoreMode == ScoreMode.GPA,
                        onCheckedChange = { onModeChange(if (it) ScoreMode.GPA else ScoreMode.WEIGHTED) },
                    )
                    Text(
                        "GPA",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.scoreMode == ScoreMode.GPA) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                    )
                }
            }

            // 加权模式：学期筛选
            if (state.scoreMode == ScoreMode.WEIGHTED && state.semesters.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = state.semesterFilter.isBlank(),
                        onClick = { onSemesterFilter("") },
                        label = { Text("全部", fontSize = 11.sp) },
                    )
                    state.semesters.forEach { sem ->
                        FilterChip(
                            selected = state.semesterFilter == sem,
                            onClick = { onSemesterFilter(sem) },
                            label = { Text(sem, fontSize = 11.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // 主数值
            Row(verticalAlignment = Alignment.Bottom) {
                val value = when (state.scoreMode) {
                    ScoreMode.WEIGHTED -> state.weightedResult?.let { SCORE_FMT.format(it.score) } ?: "—"
                    ScoreMode.GPA -> state.gpa?.gpa?.toString() ?: "—"
                }
                Text(
                    value,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.scoreMode == ScoreMode.WEIGHTED) "分" else "GPA",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            // 副信息
            val subtitleText = if (state.scoreMode == ScoreMode.WEIGHTED) {
                val r = state.weightedResult
                if (r != null) {
                    "纳入 ${r.courseCount} 门必修 · 共 ${r.totalCredits} 学分" +
                        if (state.excludedKcdm.isNotEmpty()) "（已排除 ${state.excludedKcdm.size} 门）" else ""
                } else "没有可计算的必修课数字成绩"
            } else {
                state.gpa?.let {
                    "专业排名 ${it.rank}/${it.totalStudents} · 已获学分 ${it.earnedCredits} · 通过 ${it.passedCourses} 门"
                } ?: "GPA 信息待刷新获取"
            }
            Text(
                subtitleText,
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

            // 加权模式：课程勾选入口
            if (state.scoreMode == ScoreMode.WEIGHTED && state.weightEligible.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showCourseSelector = !showCourseSelector },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showCourseSelector) "收起课程选择" else "自定义纳入计算的课程（${state.weightEligible.count { it.second }}/${state.weightEligible.size}）")
                }
                if (showCourseSelector) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        state.weightEligible.forEach { (grade, included) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = included,
                                    onCheckedChange = { onToggleCourse(grade.kcdm) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(grade.kcmc, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${grade.xnxqmc} · ${grade.xf}学分 · ${grade.zzcj}分",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
