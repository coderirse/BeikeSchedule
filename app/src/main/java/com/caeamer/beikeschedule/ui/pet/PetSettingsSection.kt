package com.caeamer.beikeschedule.ui.pet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun PetSettingsSection(viewModel: PetViewModel = viewModel()) {
    val settings by viewModel.preferences.collectAsStateWithLifecycle()
    val prefs = settings ?: return
    var editAddress by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    Text("课表小宠物", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            PetToggle("显示课表小宠物", "大肥鱼 · deepseek酱，自由拖动、随机走动", prefs.enabled, viewModel::enabled)
            HorizontalDivider()
            Text("自动闲聊频率", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PetFrequency.entries.forEach { f ->
                    FilterChip(selected = prefs.frequency == f, onClick = { viewModel.frequency(f) }, label = { Text(f.label) })
                }
            }
            Text(when (prefs.frequency) {
                PetFrequency.QUIET -> "约 3–5 分钟一句"; PetFrequency.MEDIUM -> "约 1–2 分钟一句"; PetFrequency.LIVELY -> "约 30–60 秒一句"
            } + " · 点击宠物也能说话", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().clickable { draft = prefs.address; editAddress = true }.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("宠物怎么称呼你")
                Text(prefs.address.ifBlank { "不使用称呼" }, color = MaterialTheme.colorScheme.primary)
            }
            PetToggle("宠物课前提示", "只在课表页说话，不改变系统通知", prefs.courseHints, viewModel::courses)
            PetToggle("宠物考试提示", "提示今天、明天的考试，临近开考优先", prefs.examHints, viewModel::exams)
            PetToggle("宠物自动闲聊", "名句、打气和日常碎碎念；可随时点宠物说话", prefs.chatter, viewModel::chatter)
        }
    }
    if (editAddress) AlertDialog(
        onDismissRequest = { editAddress = false }, title = { Text("宠物怎么称呼你") },
        text = { OutlinedTextField(value = draft, onValueChange = { draft = it.take(20) }, singleLine = true,
            label = { Text("自定义称呼，最多 20 字") }, supportingText = { Text("默认“同学”，留空则不使用称呼") }) },
        confirmButton = { TextButton(onClick = { viewModel.address(draft); editAddress = false }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { editAddress = false }) { Text("取消") } },
    )
}

@Composable
private fun PetToggle(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, role = Role.Switch, onValueChange = onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = value, onCheckedChange = null)
    }
}
