package com.example.beikeschedule.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.local.SectionTimeEntity
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.model.SectionMap
import com.example.beikeschedule.model.WeekUtils
import com.example.beikeschedule.ui.theme.CourseColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

private val WEEKDAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onImportClick: () -> Unit = {}, viewModel: ScheduleViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val reminderEnabled by viewModel.reminderEnabled.collectAsState()
    val reminderMinutes by viewModel.reminderMinutes.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 开启上课提醒前需要先拿到通知权限（Android 13+）
    var pendingEnableReminder by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingEnableReminder) viewModel.setReminder(true, reminderMinutes)
        pendingEnableReminder = false
    }

    var weekMenuExpanded by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<CourseEntity?>(null) }
    var editingCourse by remember { mutableStateOf<CourseEntity?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val totalWeeks = state.semester.totalWeeks
    val pagerState = rememberPagerState(
        initialPage = (state.currentWeek ?: 1) - 1,
        pageCount = { totalWeeks },
    )

    // Pager 滑动 → 同步选中周
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { viewModel.selectWeek(it + 1) }
    }
    // 开学日期设置后（currentWeek 变化）跳到当前周
    LaunchedEffect(state.currentWeek) {
        state.currentWeek?.let { pagerState.scrollToPage(it - 1) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.semester.name.ifBlank { "贝壳课表" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { weekMenuExpanded = true }) {
                            Text("第${state.selectedWeek}周 ▾")
                        }
                        DropdownMenu(
                            expanded = weekMenuExpanded,
                            onDismissRequest = { weekMenuExpanded = false },
                        ) {
                            (1..totalWeeks).forEach { w ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "第${w}周" + when {
                                                w != state.currentWeek -> ""
                                                state.inHoliday -> "（假期后）"
                                                else -> "（本周）"
                                            },
                                            fontWeight = if (w == state.currentWeek) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        weekMenuExpanded = false
                                        scope.launch { pagerState.animateScrollToPage(w - 1) }
                                    },
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (state.currentWeek != null && state.selectedWeek != state.currentWeek) {
                        IconButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(state.currentWeek!! - 1) }
                        }) {
                            Icon(Icons.Default.DateRange, contentDescription = "回到本周")
                        }
                    }
                    IconButton(onClick = onImportClick) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "从教务系统导入")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "学期设置")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingCourse = null
                showEditDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "添加课程")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loaded && state.courses.isEmpty()) {
                EmptyState(
                    onLoadSample = { viewModel.loadSampleData() },
                    onImportClick = onImportClick,
                    onAdd = {
                        editingCourse = null
                        showEditDialog = true
                    },
                )
            } else {
                DateRow(
                    week = state.selectedWeek,
                    semester = state.semester,
                    today = LocalDate.now(),
                )
                if (state.inHoliday && state.nextWeekMonday != null) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            "假期中 · ${state.nextWeekMonday} 进入第${state.currentWeek}周",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                ) { page ->
                    WeekGrid(
                        week = page + 1,
                        courses = state.scheduledCourses,
                        sectionTimes = state.sectionTimes,
                        onCourseClick = { detailCourse = it },
                    )
                }
                if (state.unscheduledCourses.isNotEmpty()) {
                    UnscheduledStrip(
                        courses = state.unscheduledCourses,
                        onCourseClick = { detailCourse = it },
                    )
                }
            }
        }
    }

    detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            sectionTimes = state.sectionTimes,
            isSample = course.source == CourseEntity.SOURCE_SAMPLE,
            onDismiss = { detailCourse = null },
            onEdit = {
                detailCourse = null
                editingCourse = course
                showEditDialog = true
            },
            onDelete = {
                viewModel.deleteCourse(course.id)
                detailCourse = null
            },
        )
    }

    if (showEditDialog) {
        CourseEditDialog(
            initial = editingCourse,
            totalWeeks = totalWeeks,
            onDismiss = { showEditDialog = false },
            onSave = {
                viewModel.saveCourse(it)
                showEditDialog = false
            },
        )
    }

    if (showSettings) {
        SemesterSettingsDialog(
            current = state.semester,
            hasSample = state.hasSample,
            reminderEnabled = reminderEnabled,
            reminderMinutes = reminderMinutes,
            themeMode = themeMode,
            onDismiss = { showSettings = false },
            onSave = { viewModel.saveSemester(it) },
            onReminderChange = { enabled, minutes -> viewModel.setReminder(enabled, minutes) },
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onClearSample = { viewModel.clearSampleData() },
            onRequestNotificationPermission = { onGranted ->
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    onGranted()
                } else {
                    pendingEnableReminder = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
}

@Composable
private fun EmptyState(onLoadSample: () -> Unit, onImportClick: () -> Unit, onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有课程", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "从教务系统一键导入，或先手动添加 / 载入示例课表看看效果",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onImportClick) { Text("从教务系统导入") }
        TextButton(onClick = onLoadSample) { Text("载入示例课表") }
        TextButton(onClick = onAdd) { Text("手动添加课程") }
    }
}

/** 顶部日期行：左格对齐节次列，7 天列；优先用官方教学周日历取周一日期，今天高亮。 */
@Composable
private fun DateRow(week: Int, semester: SettingsStore.SemesterConfig, today: LocalDate) {
    val monday = remember(semester, week) {
        semester.weekMondays.getOrNull(week - 1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: runCatching { LocalDate.parse(semester.firstMonday) }.getOrNull()?.plusWeeks((week - 1).toLong())
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.width(SECTION_COL_WIDTH))
        (1..7).forEach { day ->
            val date = monday?.plusDays((day - 1).toLong())
            val isToday = date == today
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "周${WEEKDAY_NAMES[day - 1]}",
                    fontSize = 12.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (date != null) {
                    Text(
                        "${date.monthValue}/${date.dayOfMonth}",
                        fontSize = 10.sp,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val SECTION_COL_WIDTH = 36.dp

/** 判断两门课的节次区间是否重叠。 */
private fun sectionsOverlap(a: CourseEntity, b: CourseEntity): Boolean =
    a.startSection <= b.endSection && b.startSection <= a.endSection

/** 拆分地点为 楼名 + 房间号（"【校本部】机械楼720" → "机械楼" / "720"）。 */
private fun splitLocation(location: String): Pair<String, String> {
    val clean = location.replace(Regex("【[^】]*】"), "").trim()
    val m = Regex("^(.*?)([\\dA-Za-z]+[-\\d]*)$").find(clean)
    return if (m != null && m.groupValues[1].isNotBlank()) {
        m.groupValues[1].trim() to m.groupValues[2]
    } else {
        clean to ""
    }
}

/** 一周课表网格：左节次列 + 7 天列，课程块按节次绝对定位。 */
@Composable
private fun WeekGrid(
    week: Int,
    courses: List<CourseEntity>,
    sectionTimes: List<SectionTimeEntity>,
    onCourseClick: (CourseEntity) -> Unit,
) {
    val timeMap = remember(sectionTimes) { sectionTimes.associateBy { it.section } }
    Row(Modifier.fillMaxSize()) {
        // 节次列
        // 节次列：按 6 大节显示（一~六 + 起止时间），行高按小节数加权
        Column(Modifier.width(SECTION_COL_WIDTH).fillMaxHeight()) {
            SectionMap.BIG_SECTIONS.forEachIndexed { index, range ->
                Column(
                    modifier = Modifier.weight(range.count().toFloat()).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(SectionMap.BIG_NAMES[index], fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    timeMap[range.first]?.let {
                        Text(it.startTime, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    timeMap[range.last]?.let {
                        Text(it.endTime, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 7 天列
        (1..7).forEach { day ->
            // 同一格可能有多门不同周次的课：本周课程优先占位，非本周课程只在空位淡化显示，避免重叠
            val dayCourses = remember(courses, day, week) {
                val sorted = courses.filter { it.dayOfWeek == day }
                    .sortedByDescending { it.hasClassOnWeek(week) }
                val shown = mutableListOf<CourseEntity>()
                sorted.forEach { c ->
                    if (shown.none { sectionsOverlap(it, c) }) shown += c
                }
                shown
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // 大节分隔线
                Column(Modifier.fillMaxSize()) {
                    SectionMap.BIG_SECTIONS.forEach { range ->
                        Box(
                            Modifier
                                .weight(range.count().toFloat())
                                .fillMaxWidth()
                                .padding(vertical = 0.5.dp)
                                .alpha(0.06f)
                                .background(MaterialTheme.colorScheme.onSurface),
                        )
                    }
                }
                dayCourses.forEach { course ->
                    CourseCard(
                        course = course,
                        active = course.hasClassOnWeek(week),
                        onClick = { onCourseClick(course) },
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CourseCard(
    course: CourseEntity,
    active: Boolean,
    onClick: () -> Unit,
) {
    val (bg, fg) = CourseColors.of(course.colorIndex)
    // 13 节特殊加课钳制到第 12 节区间显示（网格按 12 小节排版）
    val clampedStart = course.startSection.coerceAtMost(SectionMap.TOTAL_SMALL_SECTIONS)
    val clampedEnd = course.endSection.coerceAtMost(SectionMap.TOTAL_SMALL_SECTIONS)
    val span = (clampedEnd - clampedStart + 1).coerceAtLeast(1)
    val oddEven = WeekUtils.oddEvenLabel(course.weekBitmap)
    val nameMaxLines = when {
        span <= 1 -> 1
        span == 2 -> 3
        else -> 4
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .align(androidx.compose.ui.Alignment.TopCenter)
            .coursePosition(clampedStart, span)
            .padding(1.dp)
            .alpha(if (active) 1f else 0.3f)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(3.dp)) {
            Text(
                course.name,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = nameMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (span >= 2 && course.location.isNotBlank()) {
                val (building, room) = splitLocation(course.location)
                Text(
                    building,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = fg.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (room.isNotEmpty()) {
                    Text(
                        room,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        color = fg.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (span >= 2 && oddEven.isNotEmpty()) {
                Text("[$oddEven]", fontSize = 8.sp, color = fg.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * 课程块定位：一次性测量出固定高度（span/13 父高）并放置到 (startSection-1)/13 处。
 * 不能用 fillMaxHeight+偏移的组合——fillMaxHeight 会先压缩约束，导致偏移量被等比缩小。
 */
private fun Modifier.coursePosition(startSection: Int, span: Int): Modifier =
    this.layout { measurable, constraints ->
        val unit = constraints.maxHeight / SectionMap.TOTAL_SMALL_SECTIONS
        val height = (unit * span).coerceAtLeast(unit)
        val placeable = measurable.measure(
            constraints.copy(minHeight = height, maxHeight = height),
        )
        layout(placeable.width, placeable.height) {
            placeable.place(0, unit * (startSection - 1))
        }
    }

/** 无固定时间课程横向列表（实验周/网课等）。 */
@Composable
private fun UnscheduledStrip(courses: List<CourseEntity>, onCourseClick: (CourseEntity) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            "无固定时间课程",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 88.dp, bottom = 8.dp), // 避开 FAB
        ) {
            items(courses, key = { it.id }) { course ->
                val (bg, fg) = CourseColors.of(course.name.hashCode())
                Surface(
                    color = bg,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable { onCourseClick(course) },
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(course.name, fontSize = 12.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            WeekUtils.describe(course.weekBitmap),
                            fontSize = 10.sp,
                            color = fg.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}
