package com.caeamer.beikeschedule.data.remote

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 云备份快照的 Ed25519 验签（服务端 showwe /api/bs/backup）。
 *
 * ## 为什么必须验签
 *
 * 备案前 BASE_URL 是明文 HTTP，`getBackup` 返回的快照在链路上可被 MITM 整包替换；
 * 恢复又是"清空本机三源课程后整表覆盖"——不验签等于把"快照投毒"（注入钓鱼课程名/
 * 考试地点）的入口开在同一网关的任何人面前（R4 审查 R3）。快照由服务端私钥签名
 * （`showwe/server/src/bs_backup_signing_private.pem`，gitignore），公钥编译进 App。
 *
 * 签名约定：sig = Ed25519(UTF-8 字节 of `snapshot` 原始字符串) 的 Base64。
 * `snapshot` 是服务端存储的原始 JSON 文本，客户端**验签后直接解码**，
 * 不经过"对象重新序列化"，保证字节逐位一致。
 */
object BackupSignature {

    /** 备份签名公钥（X.509 SPKI，Base64），与 showwe 服务端私钥配对。 */
    private const val BACKUP_PUBLIC_KEY_SPKI_B64 =
        "MCowBQYDK2VwAyEA0LPviwcrxCe5PnUoXZEOpaLtObZbB9zP3wQ/IWBnblA="

    /** 验签快照：sig 为空 / Base64 非法 / 验签失败一律 false（宁可拒绝恢复，不接受未签名数据）。 */
    fun verify(snapshotText: String, sigBase64: String): Boolean =
        verify(snapshotText, sigBase64, releasePublicKey())

    /** 可注入公钥的重载（测试与服务端联调用）。 */
    fun verify(snapshotText: String, sigBase64: String, publicKey: java.security.PublicKey): Boolean {
        if (sigBase64.isBlank()) return false
        val sig = runCatching { Base64.getDecoder().decode(sigBase64) }.getOrNull() ?: return false
        return runCatching {
            val s = Signature.getInstance("Ed25519")
            s.initVerify(publicKey)
            s.update(snapshotText.toByteArray(Charsets.UTF_8))
            s.verify(sig)
        }.getOrDefault(false)
    }

    fun releasePublicKey(): java.security.PublicKey =
        KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(BACKUP_PUBLIC_KEY_SPKI_B64)))
}
