package com.example.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.model.SectionMap
import com.example.beikeschedule.model.WeekUtils

/** 手动添加 / 编辑课程对话框。周次通过 起止周 + 每周/单周/双周 转换为位图存储。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditDialog(
    initial: CourseEntity?,
    totalWeeks: Int,
    onDismiss: () -> Unit,
    onSave: (CourseEntity) -> Unit,
) {
    val existingWeeks = remember(initial) { initial?.let { WeekUtils.weeksOf(it.weekBitmap) } ?: emptyList() }

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var teacher by remember { mutableStateOf(initial?.teacher ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var dayOfWeek by remember { mutableIntStateOf(initial?.dayOfWeek?.takeIf { it in 1..7 } ?: 1) }
    // 手动添加按大节选择（1..6），保存时映射为小节区间
    var startBig by remember {
        mutableIntStateOf(initial?.let { SectionMap.bigIndexOf(it.startSection) + 1 } ?: 1)
    }
    var endBig by remember {
        mutableIntStateOf(initial?.let { SectionMap.bigIndexOf(it.endSection) + 1 } ?: 1)
    }
    var startWeek by remember { mutableIntStateOf(existingWeeks.minOrNull() ?: 1) }
    var endWeek by remember { mutableIntStateOf(existingWeeks.maxOrNull() ?: totalWeeks) }
    var weekType by remember {
        mutableIntStateOf(
            when (initial?.let { WeekUtils.oddEvenLabel(it.weekBitmap) }) {
                "单" -> WeekUtils.WEEK_TYPE_ODD
                "双" -> WeekUtils.WEEK_TYPE_EVEN
                else -> WeekUtils.WEEK_TYPE_ALL
            },
        )
    }

    val valid = name.isNotBlank() && startBig <= endBig && startWeek <= endWeek

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField(
                        label = "星期",
                        options = (1..7).map { it to "周${"一二三四五六日"[it - 1]}" },
                        selected = dayOfWeek, onSelect = { dayOfWeek = it },
                        modifier = Modifier.weight(1f),
                    )
                    DropdownField(
                        label = "开始节",
                        options = (1..6).map { it to "第${SectionMap.BIG_NAMES[it - 1]}大节" },
                        selected = startBig, onSelect = { startBig = it },
                        modifier = Modifier.weight(1f),
                    )
                    DropdownField(
                        label = "结束节",
                        options = (1..6).map { it to "第${SectionMap.BIG_NAMES[it - 1]}大节" },
                        selected = endBig, onSelect = { endBig = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField(
                        label = "开始周",
                        options = (1..totalWeeks).map { it to "第${it}周" },
                        selected = startWeek, onSelect = { startWeek = it },
                        modifier = Modifier.weight(1f),
                    )
                    DropdownField(
                        label = "结束周",
                        options = (1..totalWeeks).map { it to "第${it}周" },
                        selected = endWeek, onSelect = { endWeek = it },
                        modifier = Modifier.weight(1f),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(WeekUtils.WEEK_TYPE_ALL to "每周", WeekUtils.WEEK_TYPE_ODD to "单周", WeekUtils.WEEK_TYPE_EVEN to "双周")
                        .forEachIndexed { index, (type, label) ->
                            SegmentedButton(
                                selected = weekType == type,
                                onClick = { weekType = type },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            ) { Text(label) }
                        }
                }
                if (!valid) {
                    Text(
                        "课程名必填，且开始节/周不得晚于结束节/周",
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
                        CourseEntity(
                            id = initial?.id ?: 0,
                            taskId = "",
                            name = name.trim(),
                            teacher = teacher.trim(),
                            location = location.trim(),
                            dayOfWeek = dayOfWeek,
                            startSection = SectionMap.BIG_SECTIONS[startBig - 1].first,
                            endSection = SectionMap.BIG_SECTIONS[endBig - 1].last,
                            weekBitmap = WeekUtils.buildWeekBitmap(startWeek, endWeek, weekType, totalWeeks),
                            colorIndex = initial?.colorIndex ?: name.trim().hashCode(),
                            source = initial?.source ?: CourseEntity.SOURCE_MANUAL,
                        ),
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
