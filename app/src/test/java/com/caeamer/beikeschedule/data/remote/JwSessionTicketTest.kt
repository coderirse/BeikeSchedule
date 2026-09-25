package com.caeamer.beikeschedule.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * 教务会话票据的密封回归护栏。
 *
 * 关键点是 OAEP 参数必须和 Node 端 `oaepHash: 'sha256'` 对齐（摘要与 MGF1 都用 SHA-256）——
 * Android 的 "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" 默认 MGF1 是 SHA-1，
 * 一旦有人"简化"成不带 OAEPParameterSpec 的写法，服务端就会解不开票据，
 * 表现为"更新到最新版后反而登不上"。
 */
class JwSessionTicketTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun unseal(ticket: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyPair.private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        return String(cipher.doFinal(Base64.getDecoder().decode(ticket)), Charsets.UTF_8)
    }

    @Test
    fun `密封后能用配对的私钥按 OAEP-SHA256 解开`() {
        val cookie = "SESSION=abc123def456; JSESSIONID=xyz"
        val ticket = JwSessionTicket.seal(cookie, keyPair.public)
        assertNotNull(ticket)
        assertEquals(cookie, unseal(ticket!!))
    }

    @Test
    fun `空 cookie 不发票据`() {
        assertNull(JwSessionTicket.seal("", keyPair.public))
        assertNull(JwSessionTicket.seal("   ", keyPair.public))
    }

    @Test
    fun `内置公钥是合法的 RSA SPKI 且能加密`() {
        val key = JwSessionTicket.releasePublicKey()
        assertEquals("RSA", key.algorithm)
        val ticket = JwSessionTicket.seal("SESSION=x")
        assertNotNull(ticket)
        assertTrue(ticket!!.length > 100)
    }
}
