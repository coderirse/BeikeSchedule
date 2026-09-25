package com.caeamer.beikeschedule.data.remote

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云备份快照验签（纯 JVM）。锁定：有效签名通过、篡改拒绝、空签名拒绝。 */
class BackupSignatureTest {

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sign(text: String, key: PrivateKey = keyPair.private): String {
        val s = Signature.getInstance("Ed25519")
        s.initSign(key)
        s.update(text.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(s.sign())
    }

    @Test
    fun `valid signature passes`() {
        val snapshot = """{"schemaVersion":1,"courses":[]}"""
        assertTrue(BackupSignature.verify(snapshot, sign(snapshot), keyPair.public))
    }

    @Test
    fun `tampered snapshot rejected`() {
        val snapshot = """{"schemaVersion":1,"courses":[]}"""
        val tampered = """{"schemaVersion":1,"courses":[],"name":"钓鱼"}"""
        assertFalse(BackupSignature.verify(tampered, sign(snapshot), keyPair.public))
    }

    @Test
    fun `blank or invalid sig rejected`() {
        val snapshot = """{"schemaVersion":1}"""
        assertFalse(BackupSignature.verify(snapshot, ""))
        assertFalse(BackupSignature.verify(snapshot, "!!!not-base64!!!"))
    }

    @Test
    fun `test key signature rejected by release key`() {
        val snapshot = """{"schemaVersion":1}"""
        assertFalse(BackupSignature.verify(snapshot, sign(snapshot)))
    }
}
