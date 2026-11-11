package com.caeamer.beikeschedule

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.ui.pet.ai.*
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException

class PetAiChatInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val store get() = PetAiStore.get(app)
    private lateinit var original: PetAiState
    private var vm: PetAiViewModel? = null
    @Before fun prepare() = runBlocking {
        original = withTimeout(10000) { store.state.filterNotNull().first() }
        val provider = AiProvider(vendor = AiVendor.CUSTOM, baseUrl = "https://testing.example/v1", apiKey = "test-only-key", model = "test-model")
        store.update { PetAiState(providers = listOf(provider), activeProvider = provider.id) }
    }
    @After fun restore() = runBlocking { vm?.stop(); vm?.viewModelScope?.cancel(); store.update { original } }
    private fun response(chain: Interceptor.Chain, body: String, code: Int = 200, type: String = "application/json") = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(code).message("Test").header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()
    private fun show(client: PetAiClient) {
        val model = PetAiViewModel(app, client); vm = model
        compose.setContent { BeikeScheduleTheme { PetChatSheet(onDismiss = { model.stop() }, onSettings = {}, vm = model) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pet-chat-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-chat-input").assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsDisplayed()
    }
    @Test fun streamedConversationStoresExplicitMemoryAndKeepsHistory() {
        val calls = AtomicInteger()
        val client = PetAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.getAndIncrement() == 0) response(chain, """{"choices":[{"message":{"content":"","tool_calls":[{"id":"m1","function":{"name":"remember_preference","arguments":"{\"text\":\"用户喜欢简短回答\"}"}}]},"finish_reason":"tool_calls"}]}""")
            else response(chain, "data: {\"choices\":[{\"delta\":{\"content\":\"记住啦，我会说得简短些。\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", type = "text/event-stream")
        }.build())
        show(client)
        compose.onNodeWithTag("pet-chat-input").performTextInput("记住，我喜欢简短的回答")
        compose.onNodeWithText("发送").performClick()
        compose.waitUntil(15000) { store.state.value!!.messages.any { it.role == "assistant" } }
        compose.onNodeWithText("记住啦，我会说得简短些。").assertIsDisplayed()
        assertEquals("用户喜欢简短回答", store.state.value!!.memories.single().text)
        assertEquals(2, store.state.value!!.messages.size)
        compose.onNodeWithContentDescription("新聊天").performClick()
        compose.waitUntil(5000) { store.state.value!!.history().isEmpty() }
        assertEquals(2, store.state.value!!.messages.size)
    }
    @Test fun ungrantedToolRequestsNeverReceiveLearningData() {
        val calls = AtomicInteger()
        show(PetAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.getAndIncrement() == 0) response(chain, """{"choices":[{"message":{"content":"","tool_calls":[{"id":"r1","function":{"name":"read_learning_data","arguments":"{\"scopes\":[\"GRADES\",\"PROFILE\"]}"}}]},"finish_reason":"tool_calls"}]}""")
            else {
                val request = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                assertTrue(request.contains("没有授权")); assertFalse(request.contains("\"profile\"")); assertFalse(request.contains("\"grades\""))
                response(chain, """{"choices":[{"message":{"content":"成绩尚未授权，请先打开对应开关。"},"finish_reason":"stop"}]}""")
            }
        }.build()))
        compose.onNodeWithTag("pet-chat-input").performTextInput("帮我分析成绩")
        compose.onNodeWithText("发送").performClick()
        compose.waitUntil(15000) { store.state.value!!.messages.any { it.role == "assistant" } }
        compose.onNodeWithText("成绩尚未授权，请先打开对应开关。").assertIsDisplayed()
        assertTrue(store.state.value!!.messages.last().scopes.isEmpty())
    }
    @Test fun stopButtonCancelsTransportWithoutSavingPartialAssistantReply() {
        val entered = AtomicBoolean(false); val cancelled = AtomicBoolean(false)
        show(PetAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            entered.set(true)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            cancelled.set(chain.call().isCanceled()); throw IOException("test stopped")
        }.build()))
        compose.onNodeWithTag("pet-chat-input").performTextInput("你好")
        compose.onNodeWithText("发送").performClick()
        compose.waitUntil(5000) { entered.get() }
        compose.onNodeWithText("停止").performTouchInput { click() }
        compose.waitUntil(5000) { cancelled.get() }
        assertTrue(store.state.value!!.messages.none { it.role == "assistant" })
    }
    @Test fun customServicesWithoutToolsFallBackToClearlyLabelledBasicChat() {
        val calls = AtomicInteger()
        show(PetAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.getAndIncrement() == 0) response(chain, "{\"error\":\"tools unsupported\"}", code = 400)
            else {
                val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                assertFalse(AiProtocol.obj(body).containsKey("tools"))
                response(chain, """{"choices":[{"message":{"content":"你好，我们可以先聊聊。"},"finish_reason":"stop"}]}""")
            }
        }.build()))
        compose.onNodeWithTag("pet-chat-input").performTextInput("你好")
        compose.onNodeWithText("发送").performClick()
        compose.waitUntil(15000) { store.state.value!!.messages.any { it.role == "assistant" } }
        compose.onNodeWithText("本次使用基础聊天", substring = true).assertIsDisplayed()
        assertTrue(store.state.value!!.memories.isEmpty())
    }
}
