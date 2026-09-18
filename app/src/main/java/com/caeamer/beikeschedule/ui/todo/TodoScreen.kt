package com.caeamer.beikeschedule.ui.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.ui.schedule.DropdownField
import com.caeamer.beikeschedule.ui.theme.CourseColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 日程时间统一 HH:mm（24 小时制）。 */
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 日程页：按日期分组展示个人事项，支持新增/编辑/删除与每日打卡。
 * 数据来自本地 Room，与教务会话无关；提醒由 TodoReminderScheduler 独立调度。
 */
@Composable
fun TodoScreen(viewModel: TodoViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var editing by remember { mutableStateOf<TodoEntity?>(null) }   // null = 未打开表单
    var showForm by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (state.groups.isEmpty()) {
            EmptyTodo(onAdd = { showForm = true })
        } else {
            TodoList(
                state = state,
                onToggleDone = viewModel::toggleDone,
                onClick = { editing = it; showForm = true },
            )
        }
        FloatingActionButton(
            onClick = { editing = null; showForm = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "新增日程")
        }
    }

    if (showForm) {
        TodoFormSheet(
            initial = editing,
            onDismiss = { showForm = false },
            onSave = { viewModel.save(it); showForm = false },
            onDelete = editing?.let { existing ->
                { viewModel.delete(existing.id); showForm = false }
            },
        )
    }
}

@Composable
private fun EmptyTodo(onAdd: () -> Unit) {
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
        Text("还没有日程", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "点右下角 + 添加每天要做的事，到点前会提醒你",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd) { Text("新增日程") }
    }
}

@Composable
private fun TodoList(
    state: TodoUiState,
    onToggleDone: (Long) -> Unit,
    onClick: (TodoEntity) -> Unit,
) {
    val today = LocalDate.now()
    val now = LocalTime.now()
    LazyColumn(Modifier.fillMaxSize()) {
        state.groups.forEach { (date, dayTodos) ->
            item(key = "todo_header_$date") {
                val label = when (ChronoUnit.DAYS.between(today, date)) {
                    0L -> "今天"
                    1L -> "明天"
                    else -> date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA))
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            items(dayTodos, key = { "${date}_${it.id}" }) { todo ->
                TodoRow(
                    todo = todo,
                    isToday = date == today,
                    isPast = date == today && runCatching { LocalTime.parse(todo.time) }.getOrNull()?.isBefore(now) == true,
                    done = todo.id in state.doneIds,
                    onToggleDone = { onToggleDone(todo.id) },
                    onClick = { onClick(todo) },
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        item { Spacer(Modifier.height(88.dp)) } // FAB 不遮最后一项
    }
}

@Composable
private fun TodoRow(
    todo: TodoEntity,
    isToday: Boolean,
    isPast: Boolean,
    done: Boolean,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
) {
    // 色板取 (底色, 文字色)；已完成或已过时间的事项整体淡化
    val (bg, fg) = CourseColors.of(todo.colorIndex)
    val alpha = when {
        done -> 0.45f
        isPast -> 0.5f
        else -> 1f
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 打卡圈
        Surface(
            shape = CircleShape,
            color = if (done) MaterialTheme.colorScheme.primary else Color.Transparent,
            border = if (done) null
            else androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .width(26.dp)
                .height(26.dp)
                .clickable(onClick = onToggleDone),
        ) {
            if (done) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已完成",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 色块 + 文字
        Surface(
            color = bg.copy(alpha = alpha),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    todo.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (todo.note.isNotBlank()) {
                    Text(
                        todo.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = fg.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            todo.time,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isPast && !done) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alpha(alpha),
        )
    }
}

/** 新增/编辑日程的底部弹层表单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoFormSheet(
    initial: TodoEntity?,
    onDismiss: () -> Unit,
    onSave: (TodoEntity) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var repeatMode by remember { mutableStateOf(initial?.repeatMode ?: TodoEntity.REPEAT_DAILY) }
    var weekdays by remember { mutableStateOf(initial?.weekdays ?: "0111110") }
    // 用强类型保存，杜绝手输格式错误；展示时才格式化为字符串
    var date by remember { mutableStateOf(runCatching { LocalDate.parse(initial?.date) }.getOrDefault(LocalDate.now())) }
    var time by remember { mutableStateOf(runCatching { LocalTime.parse(initial?.time) }.getOrDefault(LocalTime.of(8, 0))) }
    var remindMinutes by remember { mutableStateOf(initial?.remindMinutes ?: 15) }
    var colorIndex by remember { mutableStateOf(initial?.colorIndex ?: CourseColors.defaultColorIndex) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // 三个选择器都用原生滚轮/日历，不存在格式非法，仅需事项非空即可保存
    val canSave = title.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                if (initial == null) "新增日程" else "编辑日程",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("事项") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            DropdownField(
                label = "重复",
                options = listOf(
                    TodoEntity.REPEAT_DAILY to "每天",
                    TodoEntity.REPEAT_WEEKLY to "每周",
                    TodoEntity.REPEAT_ONCE to "一次性",
                ),
                selected = repeatMode,
                onSelect = { repeatMode = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            if (repeatMode == TodoEntity.REPEAT_WEEKLY) {
                WeekdaySelector(selected = weekdays, onSelect = { weekdays = it })
                Spacer(Modifier.height(8.dp))
            }
            if (repeatMode == TodoEntity.REPEAT_ONCE) {
                // 日期用 M3 日历选择（与学期设置同一套），不再手输
                var showDatePicker by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("日期：${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
                }
                if (showDatePicker) {
                    val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDay() * 86_400_000L)
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                pickerState.selectedDateMillis?.let { millis ->
                                    date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                }
                                showDatePicker = false
                            }) { Text("确定") }
                        },
                        dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
                    ) { DatePicker(state = pickerState) }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row {
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                    Text("时间：${time.format(TIME_FMT)}")
                }
                Spacer(Modifier.width(8.dp))
                // 提前分钟滚轮
                MinutesWheel(
                    value = remindMinutes,
                    onSelect = { remindMinutes = it },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

            ColorSelector(selected = colorIndex, onSelect = { colorIndex = it })
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onSave(
                        (initial ?: TodoEntity(title = "", time = time.format(TIME_FMT))).copy(
                            title = title.trim(),
                            note = note.trim(),
                            repeatMode = repeatMode,
                            weekdays = weekdays,
                            date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            time = time.format(TIME_FMT),
                            remindMinutes = remindMinutes.coerceIn(1, 120),
                            colorIndex = colorIndex,
                        )
                    )
                }, enabled = canSave) { Text("保存") }
            }
        }
    }

    // 时间选择：M3 TimePicker（与 DatePicker 同为实验 API，风格与应用主题一致；
    // 不用系统 TimePickerDialog —— 它跟随系统主题，会出现"应用浅色 + 弹窗深色"的割裂）
    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(timeState.hour, timeState.minute)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除日程") },
            text = { Text("删除后不可恢复，确定删除「${initial?.title.orEmpty()}」吗？") },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke() }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 周一~周日 7 个可点圆点。 */
@Composable
private fun WeekdaySelector(selected: String, onSelect: (String) -> Unit) {
    val days = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEachIndexed { i, label ->
            val on = selected.getOrNull(i) == '1'
            Surface(
                shape = CircleShape,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .width(36.dp)
                    .height(36.dp)
                    .clip(CircleShape)
                    .clickable {
                        val chars = (selected + "0000000").take(7).toCharArray()
                        chars[i] = if (on) '0' else '1'
                        onSelect(String(chars))
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 课程色板圆点选择。 */
@Composable
private fun ColorSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(10) { i ->
            val (bg, _) = CourseColors.of(i)
            Box(
                Modifier
                    .width(28.dp)
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                if (i == Math.floorMod(selected, 10)) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "选中",
                        tint = CourseColors.of(i).second,
                        modifier = Modifier.width(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * 提前分钟的 1..120 滚轮选择：单列可滚列表，打开时定位到当前值，
 * 点选高亮、确定回填。单列布局简单可靠（此前的三列拼位方案在窄屏上挤爆）。
 */
@Composable
private fun MinutesWheel(value: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier) {
        Text("提前 $value 分钟")
    }
    if (show) {
        var selected by remember { mutableStateOf(value.coerceIn(1, 120)) }
        val listState = rememberLazyListState()
        // 打开时滚到当前值（值从 1 起，index = 值 − 1），当前项居中
        LaunchedEffect(Unit) {
            listState.scrollToItem(
                (selected - 1 - 3).coerceAtLeast(0),
            )
        }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("提前分钟") },
            text = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items((1..120).toList()) { n ->
                        val isSel = n == selected
                        Text(
                            if (isSel) "▶ $n 分钟" else "$n 分钟",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = n }
                                .padding(vertical = 10.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSelect(selected)
                    show = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } },
        )
    }
}
