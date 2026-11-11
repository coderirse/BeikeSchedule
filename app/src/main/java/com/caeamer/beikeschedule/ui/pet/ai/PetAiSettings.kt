package com.caeamer.beikeschedule.ui.pet.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.ui.pet.AiSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AiToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏密钥" else "显示密钥") } })
}

@Composable
fun AiProviderSettings(state: PetAiState, vm: PetAiViewModel) {
    var editor by remember { mutableStateOf<AiProvider?>(null) }
    var search by remember(state.searchMode) { mutableStateOf(state.searchMode) }
    var searchKey by remember(state.searchKey) { mutableStateOf(state.searchKey) }
    AiSection("聊天模型") {
        Text("选择预设后填写 API Key；密钥只加密保存在本机，手机直接连接所选供应商。", style = MaterialTheme.typography.bodySmall)
        state.providers.forEach { provider ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider.id == state.activeProvider, onClick = { vm.selectProvider(provider.id) })
                Column(Modifier.weight(1f)) { Text(provider.name); Text(provider.model, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { editor = provider }) { Text("编辑") }
            }
        }
        Button(onClick = { editor = AiProvider() }) { Text("添加供应商") }
    }
    AiSection("联网搜索") {
        SearchMode.entries.forEach { mode ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = search == mode, onClick = { search = mode })
                TextButton(onClick = { search = mode }) { Text(mode.label) }
            }
        }
        when (search) {
            SearchMode.OFF -> Text("不获取实时热点；仍可聊天和分析已授权的学习数据。", style = MaterialTheme.typography.bodySmall)
            SearchMode.PROVIDER -> Text("MiMo 需在供应商控制台开通联网插件；DeepSeek 使用同一 Key 调用官方 Search。自定义模型请选择独立搜索。", style = MaterialTheme.typography.bodySmall)
            SearchMode.DEEPSEEK -> { SecretField(searchKey, { searchKey = it }, "独立搜索的 DeepSeek API Key")
                Text("聊天可使用其他模型；此 Key 仅用于 DeepSeek Search，搜索另计费。", style = MaterialTheme.typography.bodySmall) }
        }
        Button(onClick = {
            if (search == SearchMode.PROVIDER && state.provider?.vendor == AiVendor.CUSTOM) vm.note.value = "自定义供应商请选择独立搜索或关闭联网"
            else if (search == SearchMode.DEEPSEEK && searchKey.isBlank()) vm.note.value = "请填写独立搜索 Key"
            else {
                val selected = search; val key = searchKey.trim()
                vm.update { it.copy(searchMode = selected, searchKey = key) }
            }
        }) { Text("保存联网设置") }
    }
    Text("模型生成和联网会消耗供应商额度。连接测试也会发起一次简短请求。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
    editor?.let { provider -> ProviderEditor(provider, vm, { editor = null }) }
}

@Composable
private fun ProviderEditor(original: AiProvider, vm: PetAiViewModel, onClose: () -> Unit) {
    var draft by remember(original.id) { mutableStateOf(original) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(test: Boolean) {
        if (busy) return
        val candidate = draft
        busy = true; result = null
        scope.launch {
            try {
                if (test) result = vm.test(candidate) else { models = vm.models(candidate); result = if (models.isEmpty()) "模型列表为空，可手动填写模型名" else "已读取 ${models.size} 个模型" }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { result = (e as? PetAiException)?.message ?: "连接失败，请检查网络、地址和 Key" }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("API 供应商") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiVendor.entries.forEach { vendor -> FilterChip(selected = draft.vendor == vendor, onClick = {
                draft = AiProvider(id = draft.id, vendor = vendor, apiKey = if (draft.vendor == vendor) draft.apiKey else ""); models = emptyList(); result = null
            }, label = { Text(vendor.label) }) }
            OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(40)) }, Modifier.fillMaxWidth(), label = { Text("供应商名称") }, singleLine = true)
            OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("HTTPS API 地址") }, singleLine = true, readOnly = draft.vendor != AiVendor.CUSTOM)
            SecretField(draft.apiKey, { draft = draft.copy(apiKey = it) }, "API Key")
            OutlinedTextField(draft.model, { draft = draft.copy(model = it.take(160)) }, Modifier.fillMaxWidth(), label = { Text("模型名") }, singleLine = true)
            if (models.isNotEmpty()) {
                Text("点选模型，或在上方手动填写", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) { models.forEach { model ->
                    TextButton(onClick = { draft = draft.copy(model = model) }) { Text(model) }
                } }
            }
            Row { TextButton(onClick = { run(false) }, enabled = !busy) { Text("读取模型") }; TextButton(onClick = { run(true) }, enabled = !busy) { Text("测试连接") } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { deleting = true }) { Text("删除此配置", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        try {
            AiProtocol.endpoint(draft, "chat/completions")
            if (draft.apiKey.isBlank() || draft.model.isBlank()) throw PetAiException("请填写 API Key 和模型名")
            vm.saveProvider(draft.copy(name = draft.name.ifBlank { draft.vendor.label })); onClose()
        } catch (e: Exception) { result = e.message }
    }) { Text("保存供应商") } }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除配置？") }, text = { Text("将移除此供应商和保存在本机的 Key。") },
        confirmButton = { TextButton(onClick = { vm.deleteProvider(draft.id); onClose() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable
fun AiProactiveSettings(state: PetAiState, vm: PetAiViewModel) {
    var interval by remember(state.proactiveMinutes) { mutableStateOf(state.proactiveMinutes.toString()) }
    var refresh by remember(state.hotspotMinutes) { mutableStateOf(state.hotspotMinutes.toString()) }
    AiSection("AI 主动搭话") {
        Text("仅在课表可见、没有弹层时运行；切页或退后台停止。未配置 API 时使用原离线频率。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("搭话间隔（1–120 分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        OutlinedTextField(refresh, { refresh = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text("热点缓存（5–1440 分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        Button(onClick = {
            val a = interval.toIntOrNull(); val b = refresh.toIntOrNull()
            if (a == null || a !in 1..120 || b == null || b !in 5..1440) vm.note.value = "请输入标注范围内的分钟数"
            else vm.update { it.copy(proactiveMinutes = a, hotspotMinutes = b) }
        }) { Text("保存频率") }
    }
}
