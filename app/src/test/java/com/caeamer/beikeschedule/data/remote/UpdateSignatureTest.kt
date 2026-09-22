package com.caeamer.beikeschedule.data.remote

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新元 Ed25519 验签（纯 JVM，运行时生成密钥对，不入库私钥）。
 * 锁定：规范化 JSON 字节、验签成功/篡改拒绝、无 sig 拒绝、发布公钥可加载。
 */
class UpdateSignatureTest {

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sign(body: UpdateSignature.SignedBody, key: PrivateKey = keyPair.private): String {
        val msg = UpdateSignature.canonicalBytes(body)
        val s = Signature.getInstance("Ed25519")
        s.initSign(key)
        s.update(msg)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    private fun latest(
        body: UpdateSignature.SignedBody = UpdateSignature.SignedBody(
            versionCode = 42,
            versionName = "1.3.1",
            changelog = "fix",
            force = true,
            size = 99L,
            url = "http://example/app.apk",
        ),
        sig: String = sign(body),
    ) = CloudApi.LatestVersion(
        versionCode = body.versionCode,
        versionName = body.versionName,
        changelog = body.changelog,
        force = body.force,
        size = body.size,
        url = body.url,
        sig = sig,
    )

    @Test
    fun `canonical json is compact with fixed key order`() {
        val bytes = UpdateSignature.canonicalBytes(
            UpdateSignature.SignedBody(42, "1.3.1", "hi", true, 99L, "http://x/a.apk"),
        )
        assertEquals(
            """{"versionCode":42,"versionName":"1.3.1","changelog":"hi","force":true,"size":99,"url":"http://x/a.apk"}""",
            bytes.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `verify accepts valid signature`() {
        val ok = UpdateSignature.verify(latest(), keyPair.public)
        assertTrue(ok)
    }

    @Test
    fun `verify rejects tampered force flag`() {
        val signed = latest()
        val tampered = signed.copy(force = !signed.force, sig = signed.sig)
        assertFalse(UpdateSignature.verify(tampered, keyPair.public))
    }

    @Test
    fun `verify rejects tampered url`() {
        val signed = latest()
        val tampered = signed.copy(url = "http://evil/app.apk", sig = signed.sig)
        assertFalse(UpdateSignature.verify(tampered, keyPair.public))
    }

    @Test
    fun `verify rejects blank or bad sig`() {
        assertFalse(UpdateSignature.verify(latest(sig = ""), keyPair.public))
        assertFalse(UpdateSignature.verify(latest(sig = "!!!not-base64!!!"), keyPair.public))
    }

    @Test
    fun `release public key is loadable Ed25519`() {
        val pub = UpdateSignature.releasePublicKey()
        // JVM 可能报 "Ed25519" 或 "EdDSA"，不锁死字面量
        assertTrue(pub.algorithm.contains("Ed", ignoreCase = true))
        assertEquals("X.509", pub.format)
    }

    @Test
    fun `release key rejects test signature`() {
        // 测试密钥签的字节，发布公钥必须验不过（防误把测试钥当发布钥）
        assertFalse(UpdateSignature.verify(latest()))
    }

    @Test
    fun `release key accepts openssl reference vector`() {
        // tools/sign_update.py + keystore/update_signing_private.pem 对同一消息的输出
        val latest = CloudApi.LatestVersion(
            versionCode = 42,
            versionName = "1.3.1",
            changelog = "test",
            force = false,
            size = 10L,
            url = "http://example/a.apk",
            sig = "co3cwl99e74+3+4b0RYKFSM1ZGy97l57pzIztRdRGBQGL1ssqmfqRaW563F+DptHKDlTGABd2DjfWiwU5LaaBA==",
        )
        assertTrue(UpdateSignature.verify(latest))
    }
}
