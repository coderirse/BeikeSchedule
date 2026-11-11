package com.caeamer.beikeschedule.ui.pet

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.ui.pet.ai.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetSettingsPage(onBack: () -> Unit, ai: PetAiViewModel = viewModel()) {
    var page by rememberSaveable { mutableStateOf("home") }
    var confirmReset by remember { mutableStateOf(false) }
    val state by ai.state.collectAsStateWithLifecycle()
    val error by ai.storageError.collectAsStateWithLifecycle()
    val note by ai.note.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 保存反馈不能覆盖底部表单按钮、截走用户紧接着的点击。
    LaunchedEffect(note) { note?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); ai.note.value = null } }
    fun back() { if (page == "home") onBack() else page = "home" }
    BackHandler { back() }
    val title = when (page) { "basic" -> "互动与外观"; "api" -> "模型与联网"; "persona" -> "人格与称呼"; "data" -> "记忆与数据"; else -> "课表小宠物" }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error != null) {
                AiSection("本机 AI 数据") { Text(error!!); TextButton(onClick = { confirmReset = true }) { Text("重置本机 AI 数据") } }
                // AI 存储故障仍可控制原有离线宠物，不要求先清除 AI 数据。
                PetSettingsSection()
            } else if (state == null) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else when (page) {
                "basic" -> { PetSettingsSection(); AiProactiveSettings(state!!, ai) }
                "api" -> AiProviderSettings(state!!, ai)
                "persona" -> AiPersonaSettings(state!!, ai)
                "data" -> AiDataSettings(state!!, ai)
                else -> {
                    AiSection(state!!.petName) {
                        Icon(Icons.Default.Pets, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (state!!.configured) "${state!!.provider?.name} · ${state!!.provider?.model}" else "离线陪伴 · 配置 API 后解锁 AI 聊天与分析", style = MaterialTheme.typography.bodyMedium)
                        Text("点击课表中的宠物即可聊天；拖动仍可调整位置。", style = MaterialTheme.typography.bodySmall)
                    }
                    listOf(Triple("basic", "互动与外观", "显示、欢迎、提示与主动搭话频率"),
                        Triple("api", "模型与联网", "供应商、API Key、模型与搜索来源"),
                        Triple("persona", "人格与称呼", "名字、称呼和自由人设"),
                        Triple("data", "记忆与数据", "学习数据授权、长期记忆与本机记录")).forEach { (id, label, description) ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { page = id }) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("重置本机 AI 数据？") },
        text = { Text("将删除无法读取的 AI 配置、Key、聊天和记忆，并重新建立设备密钥。课表等学习数据不会受影响。") },
        confirmButton = { TextButton(onClick = { ai.reset(); confirmReset = false }) { Text("确认重置") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
}

@Composable
internal fun AiSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
