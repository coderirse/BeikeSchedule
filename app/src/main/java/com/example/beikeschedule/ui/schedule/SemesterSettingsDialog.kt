package com.example.beikeschedule.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.beikeschedule.data.pref.SettingsStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 学期设置：学期名 / 开学日期（第 1 周周一）/ 总周数，另含示例数据清除入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterSettingsDialog(
    current: SettingsStore.SemesterConfig,
    hasSample: Boolean,
    onDismiss: () -> Unit,
    onSave: (SettingsStore.SemesterConfig) -> Unit,
    onClearSample: () -> Unit,
) {
    var name by remember { mutableStateOf(current.name) }
    var firstMonday by remember { mutableStateOf(current.firstMonday) }
    var totalWeeks by remember { mutableIntStateOf(current.totalWeeks) }
    var showDatePicker by remember { mutableStateOf(false) }
    var weekMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学期设置") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
