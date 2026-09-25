package com.caeamer.beikeschedule.data.pref

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 云 token 的静态加密：AndroidKeyStore 里的 AES-256-GCM 密钥包裹后落 DataStore。
 *
 * 为什么不直接明文：token 是云备份的全权凭证（见 R4 审查 R2），root 设备或同 uid
 * 应用可明文读取 preferences_pb；Keystore 硬件-backed 密钥不出 TEE/StrongBox，
 * 拿到密文也无法在其他设备解密。
 *
 * 存储格式：`"enc:" + Base64(iv(12) || ciphertext)`；无前缀 = 历史明文（读取时兼容，
 * 下次 saveCloudAccount 自然迁移为密文）。解密失败（key 被清除/密文损坏）返回空串，
 * 上层按未登录处理，重新扫码登录即可，不做更多自愈。
 */
object TokenCipher {

    private const val KEY_ALIAS = "beike_cloud_token"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PREFIX = "enc:"

    /** 明文 → `"enc:" + Base64(iv || ct)`。Keystore 不可用时返回原文（功能优先，不静默丢 token）。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            iv.copyInto(out); ct.copyInto(out, iv.size)
            PREFIX + Base64.getEncoder().encodeToString(out)
        } catch (_: Exception) {
            plain
        }
    }

    /** 兼容历史明文（无前缀直接返回）；密文解密失败返回空串（视为未登录）。 */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val data = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            require(data.size > IV_BYTES)
            val spec = GCMParameterSpec(GCM_TAG_BITS, data, 0, IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
