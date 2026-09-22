package com.caeamer.beikeschedule.data.remote

import com.caeamer.beikeschedule.ui.settings.isAuthExpired
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云 token 失效异常与上层识别（纯 JVM）。 */
class CloudAuthExceptionTest {

    @Test
    fun `is IOException with message`() {
        val e = CloudAuthException("登录已过期，请重新登录")
        assertTrue(e is IOException)
        assertTrue("登录已过期，请重新登录" == e.message)
    }

    @Test
    fun `isAuthExpired matches CloudAuthException`() {
        assertTrue(CloudAuthException("x").isAuthExpired())
    }

    @Test
    fun `isAuthExpired unwraps cause chain`() {
        val wrapped = IOException("备份失败", CloudAuthException("401"))
        assertTrue(wrapped.isAuthExpired())
    }

    @Test
    fun `plain IO is not auth expired`() {
        assertFalse(IOException("网络请求失败").isAuthExpired())
    }
}
