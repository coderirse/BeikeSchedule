package com.caeamer.beikeschedule.ui.pet.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.ui.pet.PetPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

data class AiChatRuntime(val busy: Boolean = false, val text: String = "", val status: String = "", val error: String? = null)

class PetAiViewModel @JvmOverloads constructor(app: Application, private val client: PetAiClient = PetAiClient()) : AndroidViewModel(app) {
    private val store = PetAiStore.get(app)
    val state = store.state
    val storageError = store.error
    private val live = MutableStateFlow(AiChatRuntime())
    val chat = live.asStateFlow()
    val note = MutableStateFlow<String?>(null)
    private val engine = PetAiEngine(store, PetLearningContext(ScheduleRepository(app)), client, address = { PetPreferencesStore(app).preferences.first().address })
    private var chatJob: Job? = null
    @Volatile private var revision = 0
    init {
        viewModelScope.launch { state.filterNotNull().map { it.requestConfig() }.distinctUntilChanged().collect {
            stop()
        } }
    }
    fun update(transform: (PetAiState) -> PetAiState) { viewModelScope.launch {
        try { store.update(transform); note.value = "已保存" }
        catch (e: Exception) { note.value = (e as? PetAiException)?.message ?: "保存失败，请重试" }
    } }
    fun saveProvider(provider: AiProvider) = update { state ->
        AiProtocol.endpoint(provider, "chat/completions")
        if (provider.model.isBlank() || provider.apiKey.isBlank()) throw PetAiException("请填写模型名与 API Key")
        state.copy(providers = state.providers.filterNot { it.id == provider.id } + provider.copy(apiKey = provider.apiKey.trim()),
            activeProvider = provider.id, conversation = UUID.randomUUID().toString())
    }
    fun selectProvider(id: String) = update { it.copy(activeProvider = id, conversation = UUID.randomUUID().toString()) }
    fun deleteProvider(id: String) = update { it.copy(providers = it.providers.filterNot { p -> p.id == id },
        activeProvider = if (it.activeProvider == id) "" else it.activeProvider, conversation = UUID.randomUUID().toString()) }
    fun newConversation() { stop(); update { it.copy(conversation = UUID.randomUUID().toString()) } }
    fun openConversation(id: String) { stop(); update { it.copy(conversation = id) } }
    fun clearHistory() { stop(); update { it.copy(messages = emptyList(), conversation = UUID.randomUUID().toString()) } }
    fun reset() { stop(); viewModelScope.launch { try { store.reset(); note.value = "本机 AI 数据已重置" } catch (e: Exception) { note.value = e.message } } }
    fun stop() {
        revision++
        chatJob?.cancel(); chatJob = null
        if (live.value.busy) live.value = live.value.copy(busy = false, status = "已停止，可重试")
    }

    fun send(raw: String, retry: Boolean = false) {
        val question = raw.trim().take(6000)
        if (question.isEmpty() || live.value.busy) return
        val initial = state.value
        if (initial?.configured != true) { live.value = AiChatRuntime(error = "请先在宠物设置中配置 API 供应商"); return }
        val version = ++revision
        chatJob = viewModelScope.launch {
            live.value = AiChatRuntime(busy = true, status = "正在准备…")
            try {
                val id = initial.conversation
                store.update { current ->
                    val old = if (retry && current.messages.lastOrNull()?.let { it.conversation == id && it.role == "user" } == true) current.messages.dropLast(1) else current.messages
                    current.copy(messages = old + AiMessage(conversation = id, role = "user", text = question))
                }
                val answer = engine.answer(question, onText = { fragment ->
                    if (version == revision) live.value = live.value.copy(text = live.value.text + fragment)
                }, onStatus = { status -> if (version == revision) live.value = live.value.copy(status = status) })
                if (version != revision) return@launch
                store.update { current -> if (current.conversation != id || current.requestConfig() != initial.requestConfig()) current else
                    current.copy(messages = current.messages + AiMessage(conversation = id, role = "assistant", text = answer.text, scopes = answer.scopes, sources = answer.sources)) }
                live.value = AiChatRuntime(status = if (answer.tokens > 0) "本次约 ${answer.tokens} tokens" else "已完成")
            } catch (e: CancellationException) {
                if (version == revision) live.value = live.value.copy(busy = false, status = "已停止")
                throw e
            } catch (e: Exception) {
                if (version == revision) live.value = live.value.copy(busy = false, error = (e as? PetAiException)?.message ?: "连接未完成，请检查网络或供应商后重试")
            }
        }
    }
    fun retry() = state.value?.history()?.lastOrNull { it.role == "user" }?.let { send(it.text, retry = true) }

    suspend fun bubble(prompt: String): AiAnswer {
        val initial = state.value ?: throw PetAiException("AI 设置正在加载")
        return try {
            val answer = engine.answer(prompt, proactive = true)
            store.update { current -> if (current.conversation != initial.conversation || current.requestConfig() != initial.requestConfig()) current else
                current.copy(messages = current.messages + AiMessage(conversation = current.conversation, role = "assistant", text = answer.text, scopes = answer.scopes, sources = answer.sources)) }
            answer
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            note.value = (e as? PetAiException)?.message ?: "AI 连接未完成，本次使用离线陪伴"
            throw e
        }
    }
    suspend fun test(provider: AiProvider): String {
        AiProtocol.endpoint(provider, "chat/completions")
        val reply = client.chat(provider, listOf(AiProtocol.message("user", "只回复：连接成功")), emptyList()) {}
        return if (reply.text.isNotBlank()) "连接成功，模型可用" else "供应商返回空回复"
    }
    suspend fun models(provider: AiProvider) = client.models(provider)
}
