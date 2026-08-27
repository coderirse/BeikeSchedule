package com.example.beikeschedule.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.example.beikeschedule.model.WeekUtils
import com.example.beikeschedule.ui.theme.CourseColors
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val SECTIONS_PER_DAY = 13
private val WEEKDAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(viewModel: ScheduleViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

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
                                            "第${w}周" + if (w == state.currentWeek) "（本周）" else "",
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
                    onAdd = {
                        editingCourse = null
                        showEditDialog = true
                    },
                )
            } else {
                DateRow(
                    week = state.selectedWeek,
                    firstMonday = state.semester.firstMonday,
                    today = LocalDate.now(),
                )
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
            onDismiss = { showSettings = false },
            onSave = { viewModel.saveSemester(it) },
            onClearSample = { viewModel.clearSampleData() },
        )
    }
}

@Composable
private fun EmptyState(onLoadSample: () -> Unit, onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有课程", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "从教务系统导入（即将上线），或先手动添加 / 载入示例课表看看效果",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onLoadSample) { Text("载入示例课表") }
        TextButton(onClick = onAdd) { Text("手动添加课程") }
    }
}

/** 顶部日期行：左格对齐节次列，7 天列；有开学日期时显示 M/d，今天高亮。 */
@Composable
private fun DateRow(week: Int, firstMonday: String, today: LocalDate) {
    val monday = remember(firstMonday, week) {
        runCatching { LocalDate.parse(firstMonday) }.getOrNull()?.plusWeeks((week - 1).toLong())
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
        Column(Modifier.width(SECTION_COL_WIDTH).fillMaxHeight()) {
            (1..SECTIONS_PER_DAY).forEach { section ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("$section", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    timeMap[section]?.let {
                        Text(it.startTime, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 7 天列
        (1..7).forEach { day ->
            val dayCourses = remember(courses, day) { courses.filter { it.dayOfWeek == day } }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // 节次分隔线
                Column(Modifier.fillMaxSize()) {
                    repeat(SECTIONS_PER_DAY) {
                        Box(
                            Modifier
                                .weight(1f)
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
    val span = (course.endSection - course.startSection + 1).coerceAtLeast(1)
    val oddEven = WeekUtils.oddEvenLabel(course.weekBitmap)
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .align(androidx.compose.ui.Alignment.TopCenter)
            .offset(y = 0.dp)
            .fillMaxHeight(span / SECTIONS_PER_DAY.toFloat())
            .offsetFraction(y = (course.startSection - 1) / SECTIONS_PER_DAY.toFloat())
            .padding(1.dp)
            .alpha(if (active) 1f else 0.3f)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(3.dp)) {
            Text(
                course.name,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (course.location.isNotBlank()) {
                Text(
                    course.location.removePrefix("【校本部】"),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = fg.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (oddEven.isNotEmpty()) {
                Text("[$oddEven]", fontSize = 8.sp, color = fg.copy(alpha = 0.7f))
            }
        }
    }
}

/** Modifier.offsetFraction：按父高度比例偏移（需父 Box 有确定高度）。 */
private fun Modifier.offsetFraction(y: Float): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(0, (constraints.maxHeight * y).toInt())
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
