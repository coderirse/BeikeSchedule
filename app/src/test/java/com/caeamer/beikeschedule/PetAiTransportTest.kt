package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.ui.pet.ai.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class PetAiTransportTest {
    private val provider = AiProvider(vendor = AiVendor.CUSTOM, baseUrl = "https://testing.example/v1", apiKey = "test-only-not-a-real-key", model = "test-model")
    private fun response(chain: Interceptor.Chain, body: String, code: Int = 200, type: String = "text/event-stream") = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
        .header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()

    @Test fun builtinCredentialsCannotBeSentToAnOverriddenGateway() = runBlocking {
        val invoked = AtomicBoolean(false)
        val client = PetAiClient(OkHttpClient.Builder().addInterceptor { chain -> invoked.set(true); response(chain, "{}") }.build())
        try {
            client.models(AiProvider(vendor = AiVendor.DEEPSEEK, baseUrl = "https://other.example/v1", apiKey = "test-only-key"))
            fail("Expected preset endpoint validation")
        } catch (_: PetAiException) { assertFalse(invoked.get()) }
    }

    @Test fun streamingRequestUsesSelectedEndpointAndEmitsTextInOrder() = runBlocking {
        val received = StringBuilder()
        val client = PetAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://testing.example/v1/chat/completions", chain.request().url.toString())
            assertEquals("Bearer test-only-not-a-real-key", chain.request().header("Authorization"))
            response(chain, "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        }.build())
        val reply = client.chat(provider, listOf(AiProtocol.message("user", "hello")), emptyList()) { received.append(it) }
        assertEquals("你好", received.toString()); assertEquals("你好", reply.text)
    }
    @Test fun httpErrorsDoNotEchoProviderBodyOrKey() = runBlocking {
        val client = PetAiClient(OkHttpClient.Builder().addInterceptor { response(it, "private-body-test-only-not-a-real-key", 401, "application/json") }.build())
        try { client.chat(provider, listOf(AiProtocol.message("user", "hello")), emptyList()) {}; fail("Expected API failure") }
        catch (e: PetAiException) { assertFalse(e.message.orEmpty().contains("private-body")); assertFalse(e.message.orEmpty().contains(provider.apiKey)) }
    }
    @Test fun cancellingCoroutineCancelsUnderlyingNetworkCall() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val client = PetAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(2)
            cancelled.set(chain.call().isCanceled())
            throw IOException("Test connection stopped")
        }.build())
        try { withTimeout(150) { client.chat(provider, listOf(AiProtocol.message("user", "hello")), emptyList()) {} }; fail("Expected cancellation") }
        catch (_: TimeoutCancellationException) { assertTrue(cancelled.get()) }
    }
    @Test fun ignoredStreamParameterStillAcceptsValidJsonResponse() = runBlocking {
        val client = PetAiClient(OkHttpClient.Builder().addInterceptor { response(it,
            """{"choices":[{"message":{"content":"兼容回复"},"finish_reason":"stop"}]}""", type = "application/json") }.build())
        assertEquals("兼容回复", client.chat(provider, emptyList(), emptyList()) {}.text)
    }
}
