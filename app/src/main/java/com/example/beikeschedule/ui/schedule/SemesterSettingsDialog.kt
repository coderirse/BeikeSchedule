package com.example.beikeschedule.ui.schedule

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.pref.SettingsStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 学期设置：学期名 / 开学日期 / 总周数 / 上课提醒 / 隐藏课程恢复 / 示例数据清除（主题在设置 Tab）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterSettingsDialog(
    current: SettingsStore.SemesterConfig,
    hasSample: Boolean,
    hiddenCourses: List<CourseEntity>,
    reminderEnabled: Boolean,
    reminderMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (SettingsStore.SemesterConfig) -> Unit,
    onReminderChange: (enabled: Boolean, minutes: Int) -> Unit,
    onClearSample: () -> Unit,
    onRestoreCourse: (Long) -> Unit,
    onRequestNotificationPermission: (onGranted: () -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(current.name) }
    var firstMonday by remember { mutableStateOf(current.firstMonday) }
    var totalWeeks by remember { mutableIntStateOf(current.totalWeeks) }
    var showDatePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // —— 学期 ——
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("学期名（如 2026-2027-1）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (firstMonday.isBlank()) "选择开学日期（第 1 周周一）"
                        else "开学日期：$firstMonday",
                    )
                }
                DropdownField(
                    label = "总周数",
                    options = listOf(16, 18, 20, 22, 25).map { it to "${it}周" },
                    selected = totalWeeks, onSelect = { totalWeeks = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (current.weekMondays.isNotEmpty()) {
                        "教学周日历：来自教务系统（${current.weekMondays.size} 周，含放假跳周），" +
                            "日期与当前周以官方日历为准，上方开学日期仅作备用"
                    } else {
                        "教学周日历：未导入，按开学日期逐周推算；从教务导入课表后自动获取官方日历"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // —— 上课提醒 ——
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("上课提醒", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { want ->
                            if (want) {
                                onRequestNotificationPermission { onReminderChange(true, reminderMinutes) }
                            } else {
                                onReminderChange(false, reminderMinutes)
                            }
                        },
                    )
                }
                if (reminderEnabled) {
                    DropdownField(
                        label = "提前提醒",
                        options = listOf(5, 10, 15, 20, 30, 45).map { it to "$it 分钟" },
                        selected = reminderMinutes,
                        onSelect = { onReminderChange(true, it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                    ) {
                        Text(
                            "系统未授予精确闹钟权限，提醒可能延迟几分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text("去开启精确闹钟") }
                    }
                }

                HorizontalDivider()

                // —— 隐藏课程（教务导入课程可隐藏，此处恢复）——
                Text("隐藏的课程", style = MaterialTheme.typography.titleSmall)
                if (hiddenCourses.isEmpty()) {
                    Text(
                        "暂无隐藏课程",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    hiddenCourses.forEach { course ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                course.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { onRestoreCourse(course.id) }) { Text("恢复") }
                        }
                    }
                }

                HorizontalDivider()

                // —— 主题在底部 Tab「设置」中 ——
                if (hasSample) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        onClearSample()
                        onDismiss()
                    }) {
                        Text("清除示例课表", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(current.copy(name = name.trim(), firstMonday = firstMonday, totalWeeks = totalWeeks))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching { LocalDate.parse(firstMonday) }.getOrNull()
                ?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        firstMonday = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
