package com.caeamer.beikeschedule.data.remote

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 贝壳教学平台（smartclass）的请求签名与配置解密。
 *
 * ## 为什么需要这一层
 *
 * `ustb.smartclass.cn` 的无课教室接口**不需要登录**，但每个请求都必须带一个
 * `csrkToken` 查询参数，否则服务端返回 `{"code":-1,"msg":"服务端异常"}`（实测）。
 * 生成 token 需要一个密钥 `csrkKey`，而它藏在 `/config.json` 的 `domainConfig` 字段里，
 * 且被 **AES 加密**过 —— 站点前端自己解密后才用。
 *
 * ## 已实测确定的算法参数（2026-09 验证）
 *
 * - 算法：**AES-256-CBC / PKCS7**
 * - Key：`"80bdbdbaf7494add99198960d715d41b"` 的 **UTF-8 字节**（32 字节，无需 KDF）
 * - IV ：`"bdbaf7494add9919"` 的 **UTF-8 字节**（16 字节）
 * - 密文：`domainConfig` 的 hex 字符串解码（960 字节）
 *
 * 这组参数是逆向站点 `hberJs` bundle 里的 `enHelp.De` 后实测确认的，**不是猜的**。
 * 注意站点实现里的一个坑：`enHelp.aa` 初看像字面量 `"8!0y"`，实际那是 RC4 的密钥，
 * 真正的 Key 是 RC4 解出来的 `"80bd…d41b"`（32 字符）。按字面量取会一直解不开。
 *
 * **安全边界（R4 审查备注）**：本对象的 AES-256-CBC Key/IV 与 csrkKey 都是编译期常量
 * （逆向所得，无法避免），`genToken` 按可预测的时间戳逐位取 key 字符——等于公开算法。
 * 它只有防滥用价值，**没有保密性/完整性价值**；绝不要把这个模式（固定 IV + 硬编码密钥）
 * 复用到云同步等真实凭证场景。
 */
object SmartClassCrypto {

    /** 解密 `domainConfig` 用的 AES-256 密钥（UTF-8 字节）。 */
    private const val CONFIG_KEY = "80bdbdbaf7494add99198960d715d41b"

    /** 解密 `domainConfig` 用的 IV（UTF-8 字节）。 */
    private const val CONFIG_IV = "bdbaf7494add9919"

    /**
     * 内置兜底 `csrkKey`（发行时打包）。
     *
     * 只在"拉取 `/config.json` 失败"且"本地缓存也没有"时使用。服务端轮换 key 后
     * 这个值会失效，届时表现为服务端返回 `csrf key validate error` —— 见 [isTokenRejected]，
     * 上层据此触发重新拉取，而不是直接报错给用户。
     */
    const val BUILT_IN_CSRK_KEY = "s0k6e5a1t3i46kglaz9"

    /** 服务端拒绝 token 时返回的 msg（实测）。用于区分"key 失效"与"网络异常"。 */
    private const val TOKEN_REJECTED_MSG = "csrf key validate error"

    /**
     * 服务端要求 token 的时间戳落在 `[现在, 现在+5分钟]` 内（实测：-1 分钟即失败，+5 分钟仍通过）。
     * 这里统一加 [TOKEN_TIME_SKEW_MS] 的余量，既避开"设备时钟慢"的失败区间，又不到 +5 分钟上限。
     */
    const val TOKEN_TIME_SKEW_MS = 2 * 60 * 1000L

    /**
     * 解密 `/config.json` 的 `domainConfig`，返回其中的 `csrkKey`；失败返回 null。
     *
     * @param encryptedHex `domainConfig` 字段原文（hex 字符串）
     */
    fun extractCsrkKey(encryptedHex: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(CONFIG_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(CONFIG_IV.toByteArray(Charsets.UTF_8)),
        )
        val plain = String(cipher.doFinal(hexToBytes(encryptedHex)), Charsets.UTF_8)
        // 只取需要的字段，避免依赖整个 JSON 的结构（其余字段对我们无用且可能变化）
        CSRK_KEY_REGEX.find(plain)?.groupValues?.get(1)
    }.getOrNull()

    /**
     * 生成 `csrkToken`：把时间戳的**每一位数字**当作下标，去 [csrkKey] 里取字符。
     *
     * 逐位对应关系（站点 `genToken` 的实现）：
     * 从 10^12 开始逐位降幂，`k = (t - t % j) / j` 即该位数字，取 `csrkKey[k]`；
     * 毫秒时间戳共 13 位，所以结果恒为 13 个字符。
     *
     * @param csrkKey 服务端下发的密钥（长度需 ≥ 10，否则取不到 0..9 的下标）
     * @param timeMillis 用于签名的时间戳（**必须传服务器时间**，见 [TOKEN_TIME_SKEW_MS]）
     * @return 13 字符的 token；key 不合法时返回 null
     */
    fun genToken(csrkKey: String, timeMillis: Long): String? {
        if (csrkKey.length < 10) return null
        if (timeMillis <= 0) return null
        val sb = StringBuilder(13)
        var t = timeMillis
        var j = 1_000_000_000_000L
        while (j != 0L) {
            val k = ((t - (t % j)) / j).toInt()
            // 13 位毫秒时间戳的每一位都落在 0..9；超出说明时间戳异常
            if (k !in 0..9 || k >= csrkKey.length) return null
            sb.append(csrkKey[k])
            t -= k * j
            j /= 10
        }
        return sb.toString()
    }

    /**
     * 把 URL 加上 `csrkToken` 查询参数（已有查询串时用 `&` 连接）。
     * token 生成失败时原样返回（兼容入口；调用方应优先用 [signOrNull] 短路失败请求）。
     */
    fun sign(url: String, csrkKey: String, timeMillis: Long): String =
        signOrNull(url, csrkKey, timeMillis) ?: url

    /**
     * 同 [sign]，但 token 生成失败（key 非法/时间戳异常）返回 null：调用方应据此
     * **不发请求**直接失败，而不是发一个没有 csrkToken 的请求去挨服务端拒绝——
     * 那样既多耗一次 RTT，"签名被拒重校"自愈路径还会把"key 格式坏"误判为"key 轮换"反复重拉。
     */
    fun signOrNull(url: String, csrkKey: String, timeMillis: Long): String? {
        val token = genToken(csrkKey, timeMillis) ?: return null
        val sep = if (url.contains('?')) '&' else '?'
        return "$url$sep" + "csrkToken=$token"
    }

    /** 服务端响应是否表示"签名被拒绝"（token 无效或 key 已轮换）。 */
    fun isTokenRejected(responseBody: String): Boolean =
        responseBody.contains(TOKEN_REJECTED_MSG, ignoreCase = true)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex 长度必须为偶数" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    private val CSRK_KEY_REGEX = Regex("\"csrkKey\"\\s*:\\s*\"([^\"]+)\"")

    /** 仅用于单测：暴露 Key/IV 的指纹，防止有人误改常量。 */
    internal fun configKeyFingerprint(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(CONFIG_KEY.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
