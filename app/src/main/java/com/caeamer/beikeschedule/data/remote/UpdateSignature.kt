package com.caeamer.beikeschedule.data.remote

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 自有更新源 `/api/bs/app/latest` 的 Ed25519 分离验签。
 *
 * ## 为什么必须验签
 *
 * `BASE_URL` 在 ICP 备案前是明文 HTTP，MITM 可改写 `force` 与 APK `url` 做强更劫持。
 * 元数据验签后：无签名 / 验签失败 → **完全忽略自有源**（由调用方回退 GitHub HTTPS），
 * 绝不把未验签的 `force` 或下载链接交给用户。
 *
 * ## 签名约定（服务端必须逐字一致）
 *
 * 1. 待签消息 = 下列 JSON 的 UTF-8 字节（紧凑、键序固定、无 `sig` 字段）：
 *    `{"versionCode":<int>,"versionName":"...","changelog":"...","force":<true|false>,"size":<long>,"url":"..."}`
 * 2. `sig` = Ed25519 签名的 Base64（标准字母表，可含 `=` 填充），放在响应顶层。
 * 3. 私钥离线保管（`keystore/update_signing_private.pem`，已 gitignore）；公钥编译进 App。
 *
 * 参考实现见 `tools/sign_update.py`。算法为平台 Ed25519（minSdk 34 起系统自带）。
 */
object UpdateSignature {

    /**
     * 发布用公钥（X.509 SPKI，Base64）。**只能放公钥**；私钥在 gitignore 的 keystore/ 下。
     * 轮换密钥时改这里，并同步服务端私钥。
     */
    private const val RELEASE_PUBLIC_KEY_SPKI_B64 =
        "MCowBQYDK2VwAyEAGmb6ykQrq61R3AuUexRsKoDa4TUsi2kwFuPVeYs//y8="

    private val json = Json { encodeDefaults = true }

    /** 与服务端约定的待签消息体（键序 = 序列化声明顺序，勿改）。 */
    @Serializable
    data class SignedBody(
        val versionCode: Int = 0,
        val versionName: String = "",
        val changelog: String = "",
        val force: Boolean = false,
        val size: Long = 0,
        val url: String = "",
    )

    /** 规范化待签字节：紧凑 JSON，键序固定。 */
    fun canonicalBytes(body: SignedBody): ByteArray =
        json.encodeToString(SignedBody.serializer(), body).toByteArray(Charsets.UTF_8)

    fun canonicalBytes(latest: CloudApi.LatestVersion): ByteArray = canonicalBytes(
        SignedBody(
            versionCode = latest.versionCode,
            versionName = latest.versionName,
            changelog = latest.changelog,
            force = latest.force,
            size = latest.size,
            url = latest.url,
        ),
    )

    /** 发布公钥。 */
    fun releasePublicKey(): PublicKey = parseSpki(RELEASE_PUBLIC_KEY_SPKI_B64)

    fun parseSpki(spkiBase64: String): PublicKey {
        val der = Base64.getDecoder().decode(spkiBase64)
        return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
    }

    /**
     * 校验 [latest] 的 `sig`。签名为空、Base64 非法、密钥/签名算法失败均视为 **不通过**。
     */
    fun verify(latest: CloudApi.LatestVersion, publicKey: PublicKey = releasePublicKey()): Boolean {
        val sigText = latest.sig
        if (sigText.isBlank()) return false
        val sigBytes = runCatching { Base64.getDecoder().decode(sigText) }.getOrNull() ?: return false
        return verify(canonicalBytes(latest), sigBytes, publicKey)
    }

    /** 校验原始消息与分离签名（测试与服务端联调用）。 */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean =
        runCatching {
            val s = Signature.getInstance("Ed25519")
            s.initVerify(publicKey)
            s.update(message)
            s.verify(signature)
        }.getOrDefault(false)
}
