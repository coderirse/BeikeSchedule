package com.caeamer.beikeschedule.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CloudApi 传输层失败文案（纯 JVM）：超时/TLS/DNS 各有可行动提示，
 * 不再统一成「网络不可用」。CancellationException 不经 networkFailure 包装
 * （execute 内单独放行；这里只锁文案映射）。
 */
class CloudApiNetworkFailureTest {

    @Test
    fun `dns failure maps to network unavailable`() {
        val e = CloudApi.networkFailure(UnknownHostException("api.example"))
        assertEquals("网络不可用，请检查网络后重试", e.message)
    }

    @Test
    fun `timeout maps to retry later`() {
        val e = CloudApi.networkFailure(SocketTimeoutException("timeout"))
        assertEquals("连接超时，请稍后重试", e.message)
    }

    @Test
    fun `connect failure maps to server unreachable`() {
        val e = CloudApi.networkFailure(ConnectException("refused"))
        assertEquals("无法连接到服务器，请稍后重试", e.message)
    }

    @Test
    fun `ssl failure maps to secure connection error`() {
        val e = CloudApi.networkFailure(SSLException("handshake"))
        assertEquals("安全连接失败，请稍后重试", e.message)
    }

    @Test
    fun `generic io maps to generic network failure`() {
        val e = CloudApi.networkFailure(IOException("broken pipe"))
        assertEquals("网络请求失败，请稍后重试", e.message)
    }

    @Test
    fun `cause is preserved`() {
        val cause = SocketTimeoutException("timeout")
        val e = CloudApi.networkFailure(cause)
        assertSame(cause, e.cause)
    }

    @Test
    fun `cancellation is not treated as network failure shape`() {
        // execute 会在 catch 里 rethrow CancellationException，不会进 networkFailure；
        // 若误入，也不应丢掉取消语义以外的类型信息到用户文案以外的路径。
        val cancel = CancellationException("cancelled")
        val e = CloudApi.networkFailure(cancel)
        assertTrue(e is IOException)
        assertEquals("网络请求失败，请稍后重试", e.message)
    }
}
