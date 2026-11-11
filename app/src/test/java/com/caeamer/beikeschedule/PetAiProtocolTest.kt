package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.ui.pet.ai.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PetAiProtocolTest {
    @Test fun baseUrlsPreserveGatewayPathsAndAcceptFullEndpoint() {
        assertEquals("https://proxy.example/user/v1/chat/completions", AiProtocol.endpoint("https://proxy.example/user/v1/", "chat/completions"))
        assertEquals("https://proxy.example/user/v1/models", AiProtocol.endpoint("https://proxy.example/user/v1/chat/completions", "models"))
        assertEquals("https://api.deepseek.com/chat/completions", AiProtocol.endpoint(AiVendor.DEEPSEEK.baseUrl, "chat/completions"))
    }
    @Test fun unsafeEndpointInputsAreRejectedBeforeSendingKey() {
        listOf("http://api.example/v1", "https://name:secret@api.example/v1", "https://api.example/v1?key=secret", "file:///data/private", "https://api.example/v1#fragment").forEach {
            assertThrows(PetAiException::class.java) { AiProtocol.endpoint(it, "chat/completions") }
        }
    }
    @Test fun fragmentedToolCallArgumentsAreReassembledWithTextAndCitations() {
        val stream = AiStreamDecoder()
        stream.accept("""{"choices":[{"delta":{"content":"我看看","tool_calls":[{"index":0,"id":"call_a","function":{"name":"read_learning_data","arguments":"{\"scopes\":"}}]}}]}""")
        stream.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[\"COURSES\"]}"}}]}}]}""")
        stream.accept("""{"choices":[{"delta":{"annotations":[{"type":"url_citation","url":"https://example.org/news","title":"新闻","publish_time":"2026-11-11"}]},"finish_reason":"tool_calls"}],"usage":{"total_tokens":25}}""")
        val reply = stream.result()
        assertEquals("我看看", reply.text)
        assertEquals("call_a", reply.tools.single().id)
        assertEquals("read_learning_data", reply.tools.single().name)
        assertEquals("""{"scopes":["COURSES"]}""", reply.tools.single().arguments)
        assertEquals("2026-11-11", reply.sources.single().date)
        assertEquals(25, reply.tokens)
    }
    @Test fun nonStreamingCompatibleServiceIsAccepted() {
        val stream = AiStreamDecoder()
        stream.accept("""{"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}""")
        assertEquals("你好", stream.result().text)
    }
    @Test fun interruptedAndTruncatedRepliesDoNotBecomeSuccessfulAnswers() {
        val stream = AiStreamDecoder()
        stream.accept("""{"choices":[{"delta":{"content":"只有一半"}}]}""")
        assertThrows(PetAiException::class.java) { stream.result() }
        assertThrows(PetAiException::class.java) { stream.accept("""{"choices":[{"delta":{},"finish_reason":"length"}]}""") }
    }
    @Test fun excessiveToolCallIndexStopsWithTypedError() {
        val stream = AiStreamDecoder()
        assertThrows(PetAiException::class.java) {
            stream.accept("""{"choices":[{"delta":{"tool_calls":[{"index":16,"id":"call_x","function":{"name":"f","arguments":"{}"}}]}}]}""")
        }
    }
    @Test fun unknownResponseOrHtmlFailsWithoutEchoingSecrets() {
        assertThrows(PetAiException::class.java) { AiProtocol.obj("<html>secret-token</html>") }
        assertFalse(AiProtocol.error(401).contains("secret"))
        assertFalse(AiProvider(apiKey = "private-secret").toString().contains("private-secret"))
        assertFalse(PetAiState(searchKey = "private-secret").toString().contains("private-secret"))
    }
    @Test fun sourceLinksRejectExecutableOrCredentialBearingUrls() {
        val links = AiProtocol.json.parseToJsonElement("""[{"url":"javascript:alert(1)"},{"url":"https://u:p@evil.example"},{"url":"https://news.example/article","title":"报道"}]""")
        assertEquals(listOf("https://news.example/article"), AiProtocol.sources(links).map { it.url })
    }
    @Test fun permissionIntersectionNeverExpandsModelRequests() {
        val request = AiProtocol.obj("""{"scopes":["COURSES","GRADES","PASSWORD","PROFILE"]}""")
        assertEquals(setOf(LearningScope.COURSES), PetLearningContext.requestedScopes(request, setOf(LearningScope.COURSES)))
        assertTrue(PetLearningContext.requestedScopes(request, emptySet()).isEmpty())
    }
    @Test fun revokedPermissionsExcludeDerivedHistoryAndMemory() {
        val state = PetAiState(conversation = "chat", grants = setOf(LearningScope.COURSES), messages = listOf(
            AiMessage(conversation = "chat", role = "assistant", text = "成绩", scopes = setOf(LearningScope.GRADES)),
            AiMessage(conversation = "chat", role = "assistant", text = "课表", scopes = setOf(LearningScope.COURSES)),
        ), memories = listOf(AiMemory(text = "私人成绩", scopes = setOf(LearningScope.GRADES)), AiMemory(text = "喜欢简短回答")))
        assertEquals(listOf("课表"), state.history().map { it.text })
        assertEquals(listOf("喜欢简短回答"), state.allowedMemories().map { it.text })
        assertNotEquals(state.conversation, state.withGrants(emptySet()).conversation)
        assertTrue(state.copy(memoryEnabled = false).allowedMemories().isEmpty())
    }
    @Test fun messageWritesDoNotChangeRequestConfigurationButSecretsAndPermissionsDo() {
        val provider = AiProvider(apiKey = "a")
        val state = PetAiState(providers = listOf(provider), activeProvider = provider.id)
        assertEquals(state.requestConfig(), state.copy(messages = listOf(AiMessage(conversation = "c", role = "user", text = "hi"))).requestConfig())
        assertNotEquals(state.requestConfig(), state.copy(providers = listOf(provider.copy(apiKey = "b"))).requestConfig())
        assertNotEquals(state.requestConfig(), state.copy(grants = setOf(LearningScope.GRADES)).requestConfig())
    }
}
