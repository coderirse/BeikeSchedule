package com.caeamer.beikeschedule.data.remote

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec

/**
 * Ed25519 验签（RFC 8032）：优先平台 provider，不可用/失败时回落内置纯 Java 实现。
 *
 * **为什么必须兜底（2026-10-05 真机踩坑）**：部分设备/ROM 没有
 * `KeyFactory.getInstance("Ed25519")`（平台 Conscrypt 的 Ed25519 KeyFactory 支持并不覆盖
 * 所有机型/版本），而异常原先直接逃出调用方：更新源验签失败会静默回退 GitHub
 * （用户端表现："检查更新每次都跳 GitHub 页"），云恢复验签失败则会以晦涩异常终止恢复。
 * 内置实现（`net.i2p.crypto:eddsa`）与平台实现同曲线同编码，签名值互认。
 *
 * 两条验签链路（更新元数据 [UpdateSignature]、云快照 [BackupSignature]）共用本文件。
 */
internal object Ed25519 {

    /** 平台 provider 解析出的公钥；本机不支持时返回 null（**不抛**）。 */
    fun platformKey(spkiB64: String): PublicKey? = runCatching { parseSpki(spkiB64) }.getOrNull()

    /** 直接解析 SPKI（测试用；设备不支持时会抛 NoSuchAlgorithmException）。 */
    fun parseSpki(spkiB64: String): PublicKey =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spkiB64)))

    /** SPKI DER 尾部的原始 32 字节公钥（Ed25519 SPKI 固定 44 字节 = 12 前缀 + 32 公钥）。 */
    fun rawKey(spkiB64: String): ByteArray =
        Base64.getDecoder().decode(spkiB64).takeLast(32).toByteArray()

    /** 从平台 PublicKey 反推原始 32 字节（encoded 尾部）；拿不到时返回 null。 */
    fun rawKeyOf(publicKey: PublicKey): ByteArray? =
        runCatching { publicKey.encoded?.takeLast(32)?.toByteArray() }
            .getOrNull()
            ?.takeIf { it.size == 32 }

    /**
     * 验签：先平台实现（[platformKey] 非 null 时），再内置实现。
     *
     * @param platformKey 平台公钥；null 表示本机缺 provider（直接走内置实现）
     * @param fallbackRawKey 内置实现用的原始 32 字节公钥
     */
    fun verify(
        message: ByteArray,
        signature: ByteArray,
        platformKey: PublicKey?,
        fallbackRawKey: ByteArray,
    ): Boolean {
        if (platformKey != null && runCatching { verifyWithPlatform(message, signature, platformKey) }
                .getOrDefault(false)
        ) {
            return true
        }
        return runCatching { verifyWithEngine(message, signature, fallbackRawKey) }.getOrDefault(false)
    }

    private fun verifyWithPlatform(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        val s = Signature.getInstance("Ed25519")
        s.initVerify(publicKey)
        s.update(message)
        return s.verify(signature)
    }

    private fun verifyWithEngine(message: ByteArray, signature: ByteArray, rawKey: ByteArray): Boolean {
        val engine = EdDSAEngine()
        engine.initVerify(
            EdDSAPublicKey(
                EdDSAPublicKeySpec(rawKey, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)),
            ),
        )
        engine.update(message)
        return engine.verify(signature)
    }
}
