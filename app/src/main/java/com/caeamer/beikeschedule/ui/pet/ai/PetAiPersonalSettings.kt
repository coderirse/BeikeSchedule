package com.caeamer.beikeschedule.ui.pet.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caeamer.beikeschedule.ui.pet.AiSection
import com.caeamer.beikeschedule.ui.pet.PetViewModel

@Composable
fun AiPersonaSettings(state: PetAiState, vm: PetAiViewModel, pet: PetViewModel = viewModel()) {
    var name by remember(state.petName) { mutableStateOf(state.petName) }
    var persona by remember(state.persona) { mutableStateOf(state.persona) }
    val preferences by pet.preferences.collectAsStateWithLifecycle()
    var address by remember(preferences?.address) { mutableStateOf(preferences?.address.orEmpty()) }
    AiSection("它是谁") {
        OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("宠物名字") }, singleLine = true)
        Text("人设可以写性格、说话方式、背景和相处习惯；会同时用于聊天与主动气泡。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(persona, { persona = it.take(6000) }, Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text("自定义人格") }, minLines = 6)
        Row {
            TextButton(onClick = { persona = "你是温柔而有边界感的课表小搭子。先倾听，再给具体建议；不说教，不重复夸奖。" }) { Text("温柔陪伴") }
            TextButton(onClick = { persona = "你是机灵活泼、偶尔吐槽的学习搭子。表达自然、有趣，尊重用户，遇到事实严谨认真。" }) { Text("元气搭子") }
        }
        Button(onClick = {
            val savedName = name; val savedPersona = persona
            vm.update { it.copy(petName = savedName, persona = savedPersona) }
        }) { Text("保存人格") }
    }
    AiSection("怎么称呼你") {
        Text("原有称呼继续保留，也可在“互动与外观”中查看当前值。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(address, { address = it.take(20) }, Modifier.fillMaxWidth(), label = { Text("新的称呼，留空则不使用称呼") }, singleLine = true)
        Button(onClick = { pet.address(address); vm.note.value = "称呼已保存" }) { Text("保存称呼") }
    }
}

@Composable
fun AiDataSettings(state: PetAiState, vm: PetAiViewModel) {
    var editing by remember { mutableStateOf<AiMemory?>(null) }
    var draft by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AiSection("学习数据授权") {
        Text("仅在回答需要时，将打开的类别发送给当前 API 供应商。不会发送学校密码、登录会话或云账号凭证；所有学习数据只读。更改授权会开始新会话。", style = MaterialTheme.typography.bodySmall)
        LearningScope.entries.forEach { scope -> AiToggle(scope.label, "允许用于聊天和主动分析", scope in state.grants) {
            vm.update { current -> current.withGrants(if (it) current.grants + scope else current.grants - scope) }
        } }
    }
    AiSection("长期记忆") {
        AiToggle("记住偏好与约定", "根据你明确表达的偏好保存，关闭后不读取或新增；已有记忆仍留在本机供你管理。", state.memoryEnabled) { enabled -> vm.update { it.copy(memoryEnabled = enabled) } }
        if (state.memories.isEmpty()) Text("还没有长期记忆，可以在聊天中说“记住，我喜欢……”或手动添加。", style = MaterialTheme.typography.bodySmall)
        state.memories.forEach { memory ->
            Text(memory.text)
            Row { TextButton(onClick = { editing = memory; draft = memory.text }) { Text("修改") }
                TextButton(onClick = { vm.update { it.copy(memories = it.memories.filterNot { row -> row.id == memory.id }) } }) { Text("删除记忆") } }
            HorizontalDivider()
        }
        TextButton(onClick = { editing = AiMemory(text = ""); draft = "" }, enabled = state.memories.size < 100) { Text("添加记忆") }
        if (state.memories.size >= 100) Text("长期记忆已达 100 条，请先删除不再需要的内容。", style = MaterialTheme.typography.bodySmall)
    }
    AiSection("本机记录") {
        Text("本机保留最近 500 条聊天、最多 100 条长期记忆；当前 ${state.messages.size} 条聊天、${state.memories.size} 条记忆。不参与云备份和系统备份。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { confirm = "history" }) { Text("清空聊天记录") }
        TextButton(onClick = { confirm = "memory" }) { Text("清空长期记忆") }
        TextButton(onClick = { confirm = "all" }) { Text("重置全部 AI 数据", color = MaterialTheme.colorScheme.error) }
    }
    editing?.let { memory -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("长期记忆") }, text = {
        OutlinedTextField(draft, { draft = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("偏好或约定") }, minLines = 3)
    }, confirmButton = { TextButton(onClick = {
        val savedText = draft.trim()
        if (savedText.isNotBlank()) vm.update { it.copy(memories = it.memories.filterNot { row -> row.id == memory.id } + memory.copy(text = savedText)) }
        editing = null
    }) { Text("保存记忆") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    if (confirm.isNotBlank()) AlertDialog(onDismissRequest = { confirm = "" }, title = { Text("确认清除？") }, text = {
        Text(when (confirm) { "all" -> "将删除所有供应商 Key、人格、聊天和记忆；课表等学习数据不会受影响。"; "memory" -> "将删除本机所有长期记忆。"; else -> "将删除本机所有聊天记录。" })
    }, confirmButton = { TextButton(onClick = {
        when (confirm) { "all" -> vm.reset(); "memory" -> vm.update { it.copy(memories = emptyList()) }; else -> vm.clearHistory() }
        confirm = ""
    }) { Text("确认清除") } }, dismissButton = { TextButton(onClick = { confirm = "" }) { Text("取消") } })
}
