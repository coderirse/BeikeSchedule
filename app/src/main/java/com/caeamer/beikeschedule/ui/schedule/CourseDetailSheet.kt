package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.model.WeekUtils

/** 课程详情底部弹层：信息展示 + 编辑/删除（手动或示例）/隐藏（教务导入）入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: CourseEntity,
    sectionTimes: List<SectionTimeEntity>,
    isSample: Boolean,
    isImported: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onHide: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(course.name, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            if (course.teacher.isNotBlank()) {
                InfoRow(icon = { Icon(Icons.Default.Person, null) }, text = course.teacher)
            }
            if (course.location.isNotBlank()) {
                InfoRow(icon = { Icon(Icons.Default.LocationOn, null) }, text = course.location)
            }
            if (!course.isUnscheduled) {
                val start = sectionTimes.firstOrNull { it.section == course.startSection }?.startTime
                val end = sectionTimes.firstOrNull { it.section == course.endSection }?.endTime
                val time = if (start != null && end != null) "（$start - $end）" else ""
                val bigSection = com.caeamer.beikeschedule.model.SectionMap
                    .describeBigSections(course.startSection, course.endSection)
                InfoText("周${"一二三四五六日"[course.dayOfWeek - 1]} $bigSection $time")
            }
            InfoText("周数：${WeekUtils.describe(course.weekBitmap)}")

            Spacer(Modifier.height(24.dp))
            Row {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
                Spacer(Modifier.weight(1f))
                if (isImported) {
                    // 教务导入课程：只能隐藏，不能删除
                    TextButton(onClick = onHide) {
                        Icon(Icons.Default.VisibilityOff, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("隐藏", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isSample) "删除（示例）" else "删除",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: @Composable () -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
}
