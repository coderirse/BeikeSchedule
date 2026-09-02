package com.caeamer.beikeschedule.ui.grades

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearProgressIndicator as Progress
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.repo.GpaCalculator
import com.caeamer.beikeschedule.import.GradesBridge
import com.caeamer.beikeschedule.import.JwWebView
import com.caeamer.beikeschedule.import.loadAssetScript
import com.caeamer.beikeschedule.ui.schedule.DropdownField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Locale 无关的两位小数（DecimalFormat 跟随系统 locale，部分地区会输出 "91,50"）。 */
private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

/** 内嵌滚动区拦截：孩子消费完后剩余 delta 在此吃掉，到底/到顶不联动父页面滑动。 */
private fun Modifier.consumeAllScroll(): Modifier = nestedScroll(
    object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset = available
    },
)

/** 教务 Tab：成绩/考试分段 + 加权/GPA 双模式 + 学期筛选 + 课程勾选 + 学分进度。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(viewModel: GradesViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showRefreshConfirm by remember { mutableStateOf(false) }
    var detailGrade by remember { mutableStateOf<GradeEntity?>(null) }

    Scaffold(
        topBar = {
            // 紧凑矮顶栏（外层 Scaffold 不消费状态栏 inset，这里自行处理）——透明透出整屏渐变
            Surface(color = Color.Transparent) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("教务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (!state.showWebView) {
                        IconButton(onClick = { showRefreshConfirm = true }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新成绩与考试")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showRefreshConfirm) {
                AlertDialog(
                    onDismissRequest = { showRefreshConfirm = false },
                    title = { Text("重新抓取") },
                    text = { Text("将进入教务系统重新抓取成绩、GPA、考试安排与学业进度，当前本地数据会保留到抓取成功。是否继续？") },
                    confirmButton = {
                        TextButton(onClick = {
                            showRefreshConfirm = false
                            viewModel.startRefresh()
                        }) { Text("继续") }
                    },
                    dismissButton = { TextButton(onClick = { showRefreshConfirm = false }) { Text("取消") } },
                )
            }
            if (state.showWebView) {
                WebViewFetch(
                    fetching = state.fetching,
                    onFetchStart = { viewModel.onFetchStart() },
                    onResult = { gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk ->
                        viewModel.onFetchResult(gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk)
                    },
                    onError = { viewModel.onFetchError(it) },
                )
            } else if (state.grades.isEmpty() && state.exams.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("还没有成绩数据", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "登录教务系统即可自动获取成绩、GPA 与考试安排",
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
                Column(Modifier.fillMaxSize()) {
                    // 分段切换：成绩 | 考试
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        SegmentedButton(
                            selected = state.section == GradesSection.SCORES,
                            onClick = { viewModel.setSection(GradesSection.SCORES) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("成绩", style = MaterialTheme.typography.labelLarge) }
                        SegmentedButton(
                            selected = state.section == GradesSection.EXAMS,
                            onClick = { viewModel.setSection(GradesSection.EXAMS) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("考试", style = MaterialTheme.typography.labelLarge) }
                    }
                    when (state.section) {
                        GradesSection.EXAMS -> ExamListContent(state.examsSorted)
                        GradesSection.SCORES -> GradesContent(
                            state = state,
                            onModeChange = { viewModel.setScoreMode(it) },
                            onSchoolYearFilter = { viewModel.setSchoolYearFilter(it) },
                            onSemesterFilter = { viewModel.setSemesterFilter(it) },
                            onToggleCourse = { viewModel.toggleExcluded(it) },
                            onErrorDismiss = { viewModel.dismissError() },
                            onGradeClick = { detailGrade = it },
                            onToggleHideScores = { viewModel.toggleHideScores() },
                        )
                    }
                }
            }
        }
    }

    detailGrade?.let { grade ->
        CourseGradeDetailSheet(grade = grade, hideScores = state.hideScores, onDismiss = { detailGrade = null })
    }
}

/** WebView 登录 + 自动抓取。 */
@Composable
private fun WebViewFetch(
    fetching: Boolean,
    onFetchStart: () -> Unit,
    onResult: (String, String, String, String, String, String, String, String) -> Unit,
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
                pageError ?: "登录教务系统后将自动获取成绩、考试与学业进度",
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
                onResult = { gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk ->
                    webView?.post { onResult(gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk) }
                },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradesContent(
    state: GradesUiState,
    onModeChange: (ScoreMode) -> Unit,
    onSchoolYearFilter: (String) -> Unit,
    onSemesterFilter: (String) -> Unit,
    onToggleCourse: (String) -> Unit,
    onErrorDismiss: () -> Unit,
    onGradeClick: (GradeEntity) -> Unit,
    onToggleHideScores: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScoreCard(state, onModeChange, onSchoolYearFilter, onSemesterFilter, onToggleCourse, onToggleHideScores) }
        // 学分修读进度（要求来自教务接口，已完成本地按成绩汇总）
        if (state.creditRows.isNotEmpty()) {
            item(key = "credit_progress") { CreditProgressCard(state) }
        }
        state.grouped.forEach { (semester, grades) ->
            item(key = "header_$semester") {
                val failed = state.failedBySemester[semester] ?: 0
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        semester,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (failed > 0) {
                        Text(
                            "$failed 门未通过",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            items(grades, key = { it.id }) { grade ->
                GradeRow(grade, hideScores = state.hideScores, onClick = { onGradeClick(grade) })
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
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

/** 学分修读进度折叠卡：毕业总进度 + 分类别"要求（教务）vs 已完成（本地成绩汇总）"。 */
@Composable
private fun CreditProgressCard(state: GradesUiState) {
    var expanded by remember { mutableStateOf(false) }
    val progress = state.gradProgress

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("学分修读进度", style = MaterialTheme.typography.titleSmall)
                    progress?.let {
                        Text(
                            "已修 ${fmt2(it.ywcxf)}/${fmt2(it.yqxf)} 学分 · 已过 ${it.ywcms}/${it.yqms} 门",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                progress?.let { p ->
                    ProgressRow(
                        label = "毕业总要求",
                        completed = p.ywcxf,
                        required = p.yqxf,
                        highlight = true,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // 类别行可能较多，限高 + 可滚动
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .consumeAllScroll().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.creditRows.forEach { row ->
                        ProgressRow(
                            label = row.category.kclbmc +
                                if (row.category.kcxzmc.isNotBlank()) "（${row.category.kcxzmc}）" else "",
                            completed = row.completed,
                            required = row.category.yqxf,
                            transfer = row.category.yzhxf,
                            highlight = false,
                        )
                    }
                }
                Text(
                    "已完成学分按本地成绩单汇总，口径与教务网一致",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String, completed: Double, required: Double, transfer: Double = 0.0, highlight: Boolean) {
    val fraction = if (required > 0) (completed / required).toFloat().coerceIn(0f, 1f) else 0f
    val over = required > 0 && completed > required
    val barColor = when {
        over -> MaterialTheme.colorScheme.tertiary
        highlight -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${fmt2(completed)}/${fmt2(required)}" + if (transfer > 0) "（含转移 ${fmt2(transfer)}）" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        Progress(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** 考试安排列表：按日期分组 + 倒计时徽章 + 座位号。 */
@Composable
private fun ExamListContent(exams: List<ExamEntity>) {
    if (exams.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text("本学期暂无考试安排", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "教务网排考后，点右上角刷新即可获取",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val today = LocalDate.now()
    val grouped = exams.groupBy { it.ksrq.ifBlank { "时间待定" } }
        .toSortedMap(compareBy { key -> if (key == "时间待定") LocalDate.MAX else runCatching { LocalDate.parse(key) }.getOrNull() ?: LocalDate.MAX })

    LazyColumn(Modifier.fillMaxSize()) {
        grouped.forEach { (date, dayExams) ->
            item(key = "exam_header_$date") {
                val countdown = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(date)) }.getOrNull()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        date,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (countdown != null && countdown >= 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                if (countdown == 0L) "今天" else "D-$countdown",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            items(dayExams, key = { it.id }) { exam ->
                ExamRow(exam, passed = countdownDays(exam, today) < 0)
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun countdownDays(exam: ExamEntity, today: LocalDate): Long =
    runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(exam.ksrq)) }.getOrDefault(Long.MAX_VALUE)

@Composable
private fun ExamRow(exam: ExamEntity, passed: Boolean) {
    val alpha = if (passed) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                exam.kcmc,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(alpha),
            )
            val timeText = when {
                exam.kssj.isNotBlank() && exam.jssj.isNotBlank() -> "${exam.kssj}–${exam.jssj}"
                exam.kssjms.isNotBlank() -> exam.kssjms
                else -> "时间待定"
            }
            Text(
                listOfNotNull(timeText, exam.cdmc.ifBlank { null }, exam.kslx.ifBlank { null })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(alpha),
            )
        }
        if (exam.zwh.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    exam.zwh,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** 单科成绩详情弹层：排名/考核方式/性质/学分/正考补考/学院/学期。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseGradeDetailSheet(grade: GradeEntity, hideScores: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(grade.kcmc, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (hideScores) "***" else grade.zzcj,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (grade.isFailed && !hideScores) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "分",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (grade.pm.isNotBlank() && grade.zrs.isNotBlank()) {
                DetailRow("课程排名", "${grade.pm} / ${grade.zrs}")
            }
            if (grade.khfs.isNotBlank()) DetailRow("考核方式", grade.khfs)
            DetailRow("课程性质", grade.kcxz)
            if (grade.kclb.isNotBlank()) DetailRow("课程类别", grade.kclb)
            DetailRow("学分", "${fmt2(grade.xf)}")
            if (grade.bkcx.isNotBlank()) DetailRow("考试类型", grade.bkcx)
            if (grade.yxmc.isNotBlank()) DetailRow("开课学院", grade.yxmc)
            DetailRow("学期", grade.xnxqmc)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** GPA/加权成绩卡片：模式切换 + 学期筛选 + 课程勾选 + 隐私开关。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoreCard(
    state: GradesUiState,
    onModeChange: (ScoreMode) -> Unit,
    onSchoolYearFilter: (String) -> Unit,
    onSemesterFilter: (String) -> Unit,
    onToggleCourse: (String) -> Unit,
    onToggleHideScores: () -> Unit,
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
                // 模式切换：两个并列选项用 SegmentedButton（Switch 的开/关语义不贴切）
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = state.scoreMode == ScoreMode.WEIGHTED,
                        onClick = { onModeChange(ScoreMode.WEIGHTED) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("加权", style = MaterialTheme.typography.labelSmall) }
                    SegmentedButton(
                        selected = state.scoreMode == ScoreMode.GPA,
                        onClick = { onModeChange(ScoreMode.GPA) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("GPA", style = MaterialTheme.typography.labelSmall) }
                }
            }

            // 加权模式：单个下拉框，打开后两竖排（左=学期，右=学年）
            if (state.scoreMode == ScoreMode.WEIGHTED && state.schoolYears.isNotEmpty()) {
                DualFilterField(
                    semesterLabel = state.semesterFilter.ifBlank { "全部学期" },
                    schoolYearLabel = state.schoolYearFilter.ifBlank { "全部学年" },
                    semesters = listOf("" to "全部学期") + state.semestersOfSchoolYear.map { it to it },
                    schoolYears = listOf("" to "全部学年") + state.schoolYears.map { it to it },
                    selectedSemester = state.semesterFilter,
                    selectedSchoolYear = state.schoolYearFilter,
                    onSemesterSelect = onSemesterFilter,
                    onSchoolYearSelect = onSchoolYearFilter,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            // 主数值（隐藏时显示 ***；右侧小眼睛切换，默认隐藏保护隐私）
            Row(verticalAlignment = Alignment.Bottom) {
                val value = when {
                    state.hideScores -> "***"
                    state.scoreMode == ScoreMode.WEIGHTED -> state.weightedResult?.let { fmt2(it.score) } ?: "—"
                    else -> state.localGpa?.let { fmt2(it.gpa) } ?: "—"
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
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleHideScores, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(
                        if (state.hideScores) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (state.hideScores) "显示成绩" else "隐藏成绩",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
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
                // GPA 为本地 4.0 制计算；教务网排名口径是平均学分绩，仍展示作参考
                val g = state.localGpa
                val rankText = state.gpa?.let { "专业排名 ${it.rank}/${it.totalStudents}（平均学分绩口径） · " } ?: ""
                if (g != null) {
                    "${rankText}满绩 4.0 · 纳入 ${g.courseCount} 门 · 共 ${fmt2(g.credits)} 学分"
                } else "没有可计算的数字成绩"
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

            // 加权模式：课程勾选入口（行样式，与"我的"页列表行呼应，替代居中大按钮）
            if (state.scoreMode == ScoreMode.WEIGHTED && state.weightEligible.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { showCourseSelector = !showCourseSelector }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "自定义纳入计算的课程（${state.weightEligible.count { it.second }}/${state.weightEligible.size}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                }
                if (showCourseSelector) {
                    // 课程可能很多，限高 + 可滚动，避免卡片撑出屏幕且能下滑查看
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .consumeAllScroll().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
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
private fun GradeRow(grade: GradeEntity, hideScores: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(grade.kcmc, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val rankText = if (grade.pm.isNotBlank() && grade.zrs.isNotBlank()) " · 排名 ${grade.pm}/${grade.zrs}" else ""
            Text(
                listOf(grade.kcxz, grade.kclb, if (grade.bkcx.isNotBlank() && grade.bkcx != "正考") grade.bkcx else null)
                    .filterNotNull().filter { it.isNotBlank() }
                    .joinToString(" · ") + " · ${grade.xf}学分" + rankText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (hideScores) "***" else grade.zzcj,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (grade.isFailed && !hideScores) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 学年+学期双列筛选：单个下拉框，打开后左列选学期、右列选学年。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DualFilterField(
    semesterLabel: String,
    schoolYearLabel: String,
    semesters: List<Pair<String, String>>,
    schoolYears: List<Pair<String, String>>,
    selectedSemester: String,
    selectedSchoolYear: String,
    onSemesterSelect: (String) -> Unit,
    onSchoolYearSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        Row(
            Modifier
                .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "筛选：${schoolYearLabel} · ${semesterLabel}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                // 左列：学期
                Column(Modifier.weight(1f)) {
                    Text("学期", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    semesters.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 13.sp, fontWeight = if (value == selectedSemester) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { onSemesterSelect(value); expanded = false },
                        )
                    }
                }
                VerticalDivider(Modifier.height(220.dp))
                // 右列：学年
                Column(Modifier.weight(1f)) {
                    Text("学年", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    schoolYears.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 13.sp, fontWeight = if (value == selectedSchoolYear) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { onSchoolYearSelect(value); expanded = false },
                        )
                    }
                }
            }
        }
    }
}
