package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseRowBuilder
import com.caeamer.beikeschedule.model.SectionMap
import com.caeamer.beikeschedule.model.SessionExpander
import com.caeamer.beikeschedule.model.WeekUtils
import com.caeamer.beikeschedule.ui.theme.CourseColors

private val WEEKDAY_NAMES_FULL = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 色板下标范围（与 CourseEditDialog 里渲染的 0..9 个色块一致）。 */
private val COLOR_INDEX_RANGE = 0..9

/**
 * 编辑中的时段：周几 + 大节集合 + **该时段自己的**周次集合。
 * 周次必须挂在时段上而不是整门课上，见 SessionExpander.EditSession 的说明。
 */
private class SessionState(dayOfWeek: Int, bigSections: Set<Int>, weeks: Set<Int>) {
    var dayOfWeek by mutableStateOf(dayOfWeek)
    var bigSections by mutableStateOf(bigSections)
    var weeks by mutableStateOf(weeks)
}

/**
 * 手动添加 / 编辑课程对话框。
 * 周次 1..N 任意多选，**每个时段各有一套周次**；时段 = 周几 + 大节任意组合，可多个；
 * 保存时由 SessionExpander 展开为连续小节区间行（一门课多行，与导入数据同构）。
 *
 * 无固定时间课程（dayOfWeek=0，教务备注行）走单独路径：只改课程名/教师/地点/颜色/周次，
 * 不做时段展开 —— expand() 会过滤掉 dayOfWeek=0，若照常展开会产出 0 行，
 * 配合 replaceIds 直接把这门课删掉（旧版"编辑无固定时间课程 = 删除"的根因）。
 * 若同名课程同时存在有/无固定时间两种行，无固定时间的行原样透传，绝不因为不在时段编辑器里就被丢弃。
 *
 * @param initialRows 课程的全部行（多时段课程 = 多行）；编辑时加载全部时段，不限于点击的那一行。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseEditDialog(
    initialRows: List<CourseEntity>,
    totalWeeks: Int,
    /** 长按课表空白格进入时预填的时段。 */
    prefill: SessionExpander.Session? = null,
    onDismiss: () -> Unit,
    onSave: (List<CourseEntity>) -> Unit,
) {
    val initial = initialRows.firstOrNull()
    val scheduledRows = remember(initialRows) { initialRows.filter { !it.isUnscheduled } }
    val unscheduledRows = remember(initialRows) { initialRows.filter { it.isUnscheduled } }
    /** 整门课都没有固定时间 → 只编辑周次与描述字段（与 CourseRowBuilder 的判定保持一致）。 */
    val onlyUnscheduled = scheduledRows.isEmpty() && unscheduledRows.isNotEmpty()
    val allWeeks = remember(totalWeeks) { (1..totalWeeks).toSet() }

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var teacher by remember { mutableStateOf(initial?.teacher ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var selectedColor by remember {
        mutableIntStateOf(
            initialRows.firstOrNull()?.colorIndex?.takeIf { it in COLOR_INDEX_RANGE }
                ?: CourseColors.defaultColorIndex,
        )
    }
    val sessions = remember {
        val seed = when {
            // 已有时段：每个时段各自保留自己的周次（编辑不再抹平周次）
            scheduledRows.isNotEmpty() ->
                SessionExpander.toEditSessions(scheduledRows).map {
                    SessionState(it.dayOfWeek, it.bigSections, it.weeks.ifEmpty { allWeeks })
                }
            prefill != null -> listOf(SessionState(prefill.dayOfWeek, prefill.bigSections, allWeeks))
            else -> listOf(SessionState(1, setOf(0), allWeeks))
        }
        seed.toMutableStateList()
    }
    // 无固定时间课程：没有时段，只有一个共用周次集合
    var unscheduledWeeks by remember {
        mutableStateOf(
            unscheduledRows.flatMap { WeekUtils.weeksOf(it.weekBitmap) }.toSet().ifEmpty { allWeeks },
        )
    }

    val valid = name.isNotBlank() &&
        if (onlyUnscheduled) {
            unscheduledWeeks.isNotEmpty()
        } else {
            sessions.isNotEmpty() && sessions.all { it.bigSections.isNotEmpty() && it.weeks.isNotEmpty() }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加课程" else "编辑课程") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("课程名 *") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = teacher, onValueChange = { teacher = it },
                    label = { Text("教师") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = location, onValueChange = { location = it },
                    label = { Text("地点") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                // —— 课程颜色 ——
                Text("课程颜色", style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    COLOR_INDEX_RANGE.forEach { idx ->
                        val (bg, fg) = CourseColors.of(idx)
                        val selected = idx == selectedColor
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) fg else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { selectedColor = idx },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = fg,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                if (onlyUnscheduled) {
                    // 无固定时间课程：只有周次可调（实验周/网课等备注行）
                    Text("周次（可多选）", style = MaterialTheme.typography.titleSmall)
                    WeekChips(
                        totalWeeks = totalWeeks,
                        selected = unscheduledWeeks,
                        onToggle = { w ->
                            unscheduledWeeks =
                                if (w in unscheduledWeeks) unscheduledWeeks - w else unscheduledWeeks + w
                        },
                        onSet = { unscheduledWeeks = it },
                    )
                    Text(
                        "该课程没有固定上课时间，只调整周次与课程信息。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("时段（周几 + 大节 + 周次，可多个）", style = MaterialTheme.typography.titleSmall)
                    sessions.forEachIndexed { index, session ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DropdownField(
                                    label = "星期",
                                    options = (1..7).map { it to WEEKDAY_NAMES_FULL[it - 1] },
                                    selected = session.dayOfWeek,
                                    onSelect = { session.dayOfWeek = it },
                                    modifier = Modifier.weight(1f),
                                )
                                if (sessions.size > 1) {
                                    IconButton(onClick = { sessions.removeAt(index) }) {
                                        Icon(Icons.Default.Close, contentDescription = "删除此时段")
                                    }
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy((-4).dp),
                            ) {
                                SectionMap.BIG_NAMES.forEachIndexed { big, label ->
                                    FilterChip(
                                        selected = big in session.bigSections,
                                        onClick = {
                                            session.bigSections =
                                                if (big in session.bigSections) session.bigSections - big
                                                else session.bigSections + big
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            Text(
                                "周次（可多选）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            WeekChips(
                                totalWeeks = totalWeeks,
                                selected = session.weeks,
                                onToggle = { w ->
                                    session.weeks =
                                        if (w in session.weeks) session.weeks - w else session.weeks + w
                                },
                                onSet = { session.weeks = it },
                            )
                        }
                        if (index != sessions.lastIndex) HorizontalDivider()
                    }
                    OutlinedButton(
                        onClick = {
                            val last = sessions.last()
                            sessions += SessionState(last.dayOfWeek, setOf(0), last.weeks)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加时段")
                    }
                    if (unscheduledRows.isNotEmpty()) {
                        Text(
                            "另有 ${unscheduledRows.size} 条无固定时间安排，将在保存时原样保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!valid) {
                    Text(
                        if (onlyUnscheduled) {
                            "课程名必填；至少选一周"
                        } else {
                            "课程名必填；每个时段至少选一个大节和一周"
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        CourseRowBuilder.build(
                            initialRows = initialRows,
                            edit = CourseRowBuilder.Edit(
                                name = name.trim(),
                                teacher = teacher.trim(),
                                location = location.trim(),
                                colorIndex = selectedColor,
                                sessions = sessions.map {
                                    SessionExpander.EditSession(it.dayOfWeek, it.bigSections, it.weeks)
                                },
                                unscheduledWeeks = unscheduledWeeks,
                                totalWeeks = totalWeeks,
                            ),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 周次多选：数字 chip 网格 + 全选/单周/双周/清空快捷按钮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekChips(
    totalWeeks: Int,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    onSet: (Set<Int>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy((-4).dp),
        ) {
            (1..totalWeeks).forEach { w ->
                FilterChip(
                    selected = w in selected,
                    onClick = { onToggle(w) },
                    label = { Text("$w") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { onSet((1..totalWeeks).toSet()) }) { Text("全选") }
            TextButton(onClick = { onSet((1..totalWeeks).filter { it % 2 == 1 }.toSet()) }) { Text("单周") }
            TextButton(onClick = { onSet((1..totalWeeks).filter { it % 2 == 0 }.toSet()) }) { Text("双周") }
            TextButton(onClick = { onSet(emptySet()) }) { Text("清空") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> DropdownField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(value)
                    expanded = false
                })
            }
        }
    }
}
