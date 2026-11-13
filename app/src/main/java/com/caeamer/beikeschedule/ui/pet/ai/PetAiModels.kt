package com.caeamer.beikeschedule.ui.pet.ai

import kotlinx.serialization.Serializable
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
enum class AiVendor(val label: String, val baseUrl: String, val defaultModel: String) {
    MIMO("小米 MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.6-flash"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
    CUSTOM("自定义 OpenAI 兼容", "", ""),
}

@Serializable
data class AiProvider(
    val id: String = UUID.randomUUID().toString(), val vendor: AiVendor = AiVendor.MIMO,
    val name: String = vendor.label, val baseUrl: String = vendor.baseUrl,
    val model: String = vendor.defaultModel, val apiKey: String = "",
) {
    override fun toString() = "AiProvider(id=$id, vendor=$vendor, name=$name, model=$model, apiKey=***)"
}

@Serializable
enum class LearningScope(val label: String) {
    COURSES("课表"), EXAMS("考试"), TODOS("日程"), GRADES("成绩"), CREDITS("学分进度"), PROFILE("学籍"),
}

@Serializable
enum class SearchMode(val label: String) { OFF("关闭联网"), PROVIDER("当前供应商官方搜索"), DEEPSEEK("独立 DeepSeek 搜索") }

@Serializable
data class AiSource(val title: String, val url: String, val date: String = "", val retrievedAt: String = "") {
    val timeLabel: String get() {
        val raw = date.ifBlank { retrievedAt }
        val local = runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }
            .getOrElse { raw.take(16).replace('T', ' ') }
        return if (local.isBlank()) "时间未提供" else (if (date.isBlank()) "获取于 " else "发布于 ") + local
    }
}

@Serializable
data class AiMessage(
    val id: String = UUID.randomUUID().toString(), val conversation: String, val role: String,
    val text: String, val at: Long = System.currentTimeMillis(),
    val scopes: Set<LearningScope> = emptySet(), val sources: List<AiSource> = emptyList(),
)

@Serializable
data class AiMemory(
    val id: String = UUID.randomUUID().toString(), val text: String,
    val scopes: Set<LearningScope> = emptySet(), val at: Long = System.currentTimeMillis(),
)

@Serializable
data class PetAiState(
    val providers: List<AiProvider> = emptyList(), val activeProvider: String = "",
    val petName: String = "大肥鱼", val persona: String = "你是住在课表里的温柔、机灵的小搭子。关心学习，也聊有趣的日常；自然、简短，不说教，不重复口号。",
    val grants: Set<LearningScope> = emptySet(), val memoryEnabled: Boolean = true,
    val memories: List<AiMemory> = emptyList(), val messages: List<AiMessage> = emptyList(),
    val conversation: String = UUID.randomUUID().toString(),
    val searchMode: SearchMode = SearchMode.OFF, val searchKey: String = "",
    val proactiveMinutes: Int = 5, val hotspotMinutes: Int = 30,
) {
    val provider: AiProvider? get() = providers.firstOrNull { it.id == activeProvider }
    val configured: Boolean get() = provider?.let { it.apiKey.isNotBlank() && it.model.isNotBlank() && it.baseUrl.isNotBlank() } == true
    // 对话写入不应重启宠物场景；只有能力配置变化才使旧请求失效。
    fun requestConfig() = copy(messages = emptyList(), memories = emptyList(), conversation = "")
    fun history(): List<AiMessage> = messages.filter { it.conversation == conversation && grants.containsAll(it.scopes) }.takeLast(30)
    fun allowedMemories(): List<AiMemory> = if (memoryEnabled) memories.filter { grants.containsAll(it.scopes) } else emptyList()
    fun bounded() = copy(
        petName = petName.trim().take(30).ifBlank { "大肥鱼" }, persona = persona.take(6000),
        messages = messages.takeLast(500).map { it.copy(text = it.text.take(16000), sources = it.sources.take(12)) },
        memories = memories.takeLast(100).map { it.copy(text = it.text.take(500)) },
        proactiveMinutes = proactiveMinutes.coerceIn(1, 120), hotspotMinutes = hotspotMinutes.coerceIn(5, 1440),
    )
    override fun toString() = "PetAiState(providers=${providers.size}, messages=${messages.size}, apiKeys=***)"
}

/** 权限变更后新会话不继承旧上下文；旧记录只保留在本机历史中。 */
fun PetAiState.withGrants(value: Set<LearningScope>) = copy(grants = value, conversation = UUID.randomUUID().toString())

/** 主动热点是否可用：CUSTOM 官方搜索是无效组合；独立搜索需要单独的 Key。 */
fun hotspotAvailable(state: PetAiState): Boolean = state.configured && when (state.searchMode) {
    SearchMode.OFF -> false
    SearchMode.PROVIDER -> state.provider?.vendor != AiVendor.CUSTOM
    SearchMode.DEEPSEEK -> state.searchKey.isNotBlank()
}

class PetAiException(message: String, val httpCode: Int? = null) : Exception(message)
