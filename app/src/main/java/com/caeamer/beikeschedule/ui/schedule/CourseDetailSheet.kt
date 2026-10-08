package com.caeamer.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/** 周一..周日的中文单字；下标 0 起。 */
private const val WEEKDAY_NAMES = "一二三四五六日"

/** 课程详情底部弹层：信息展示 + 编辑/删除（手动或示例）/隐藏（教务导入、班级实验安排）入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: CourseEntity,
    sectionTimes: List<SectionTimeEntity>,
    isSample: Boolean,
    isImported: Boolean,
    /** 班级实验安排（服务端下发）：每次同步整组替换，只能隐藏不能删除。 */
    isLab: Boolean = false,
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
                // getOrNull 而非直接下标：防御历史脏数据（parseDayOfWeek 现已拒绝 1..7
                // 以外的星期并整行跳过，但库里可能还留着旧版导入的 xq8/xq9 行）。
                val dayName = WEEKDAY_NAMES.getOrNull(course.dayOfWeek - 1) ?: "?"
                InfoText("周$dayName $bigSection $time")
            }
            InfoText("周数：${WeekUtils.describe(course.weekBitmap)}")
            // 备注来自班级实验安排（如"8:30 到岗"）：教务导入行没有该字段，非空才显示
            if (course.note.isNotBlank()) {
                InfoText("备注：${course.note}")
            }
            if (isLab) {
                InfoText("来自班级实验安排，每次同步自动更新")
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
                Spacer(Modifier.weight(1f))
                // 三类课程（教务导入 / 自定义 / 示例）都能隐藏：隐藏 = 课表不显示、也不再提醒，
                // 可在「我的」→ 隐藏的课程里恢复。
                TextButton(onClick = onHide) {
                    Icon(
                        Icons.Default.VisibilityOff, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("隐藏", color = MaterialTheme.colorScheme.error)
                }
                // 教务导入课与班级实验安排都只能隐藏不能删除（下次导入/同步会原样回来）；
                // 自定义课与示例课保留删除。
                if (!isImported && !isLab) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
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
