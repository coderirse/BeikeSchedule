package com.example.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.model.SectionMap
import com.example.beikeschedule.model.SessionExpander
import com.example.beikeschedule.model.WeekUtils

private val WEEKDAY_NAMES_FULL = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 编辑中的时段（大节集合可任意多选，保存时展开为连续区间行）。 */
private class SessionState(dayOfWeek: Int, bigSections: Set<Int>) {
    var dayOfWeek by mutableStateOf(dayOfWeek)
    var bigSections by mutableStateOf(bigSections)
}

/**
 * 手动添加 / 编辑课程对话框。
 * 周次 1..N 任意多选；时段 = 周几 + 大节任意组合，可多个时段；
 * 保存时由 SessionExpander 展开为连续小节区间行（一门课多行，与导入数据同构）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseEditDialog(
    initial: CourseEntity?,
    totalWeeks: Int,
    /** 长按课表空白格进入时预填的时段。 */
    prefill: SessionExpander.Session? = null,
    onDismiss: () -> Unit,
    onSave: (List<CourseEntity>) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var teacher by remember { mutableStateOf(initial?.teacher ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var selectedWeeks by remember {
        mutableStateOf(
            initial?.let { WeekUtils.weeksOf(it.weekBitmap).toSet() } ?: (1..totalWeeks).toSet(),
        )
    }
    val sessions = remember {
        val seed = when {
            initial != null -> SessionExpander.toSessions(
                listOf(SessionExpander.Row(initial.dayOfWeek, initial.startSection, initial.endSection)),
            ).map { SessionState(it.dayOfWeek, it.bigSections) }
            prefill != null -> listOf(SessionState(prefill.dayOfWeek, prefill.bigSections))
            else -> listOf(SessionState(1, setOf(0)))
        }
        seed.toMutableStateList()
    }

    val valid = name.isNotBlank() &&
        selectedWeeks.isNotEmpty() &&
        sessions.isNotEmpty() &&
        sessions.all { it.bigSections.isNotEmpty() }

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

                HorizontalDivider()
                Text("周次（可多选）", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy((-4).dp),
                ) {
                    (1..totalWeeks).forEach { w ->
                        FilterChip(
                            selected = w in selectedWeeks,
                            onClick = {
                                selectedWeeks = if (w in selectedWeeks) selectedWeeks - w else selectedWeeks + w
                            },
                            label = { Text("$w") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { selectedWeeks = (1..totalWeeks).toSet() }) { Text("全选") }
                    TextButton(onClick = { selectedWeeks = (1..totalWeeks).filter { it % 2 == 1 }.toSet() }) { Text("单周") }
                    TextButton(onClick = { selectedWeeks = (1..totalWeeks).filter { it % 2 == 0 }.toSet() }) { Text("双周") }
                    TextButton(onClick = { selectedWeeks = emptySet() }) { Text("清空") }
                }

                HorizontalDivider()
                Text("时段（周几 + 大节，可多个）", style = MaterialTheme.typography.titleSmall)
                sessions.forEachIndexed { index, session ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    }
                }
                OutlinedButton(
                    onClick = { sessions += SessionState(sessions.last().dayOfWeek, setOf(0)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("添加时段")
                }

                if (!valid) {
                    Text(
                        "课程名必填；至少选一周；每个时段至少选一个大节",
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
                    val weekBitmap = SessionExpander.buildWeekBitmap(selectedWeeks, totalWeeks)
                    val rows = SessionExpander.expand(
                        sessions.map { SessionExpander.Session(it.dayOfWeek, it.bigSections) },
                    )
                    onSave(
                        rows.map { row ->
                            CourseEntity(
                                id = 0,
                                taskId = "",
                                name = name.trim(),
                                teacher = teacher.trim(),
                                location = location.trim(),
                                dayOfWeek = row.dayOfWeek,
                                startSection = row.startSection,
                                endSection = row.endSection,
                                weekBitmap = weekBitmap,
                                colorIndex = initial?.colorIndex ?: name.trim().hashCode(),
                                source = initial?.source ?: CourseEntity.SOURCE_MANUAL,
                            )
                        },
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
