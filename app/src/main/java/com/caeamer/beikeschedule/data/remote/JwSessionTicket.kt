package com.caeamer.beikeschedule.data.remote

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * 教务 SESSION cookie 的密封（RSA-OAEP-SHA256），只在云登录时交给自有服务端。
 *
 * ## 为什么需要它
 *
 * 云账号的"身份验证"必须由服务端确认"请求方确实持有该生的教务会话"，否则只凭
 * 学号+姓名发 token —— 而这两项在班级名单/群文件里总是成对出现，等于谁都能读走
 * 别人的整包备份（学号、姓名、学院班级、全部成绩含排名、考试含座位号）。
 *
 * ## 为什么不直接发明文
 *
 * 备案前 BASE_URL 是明文 HTTP（`http://112.125.88.178`）。教务 SESSION 能读写该生
 * 全部教务数据，比云 token 更敏感，明文带过去会被同一网关的中间人截获。
 * 这里用服务端 RSA 公钥密封（私钥只在服务端 `bs_jw_verify_private.pem`，不入库），
 * 链路与日志里都只有密文；服务端解开后立即向教务 /user/me 核实并丢弃。
 *
 * ## 参数必须对齐
 *
 * Android 的 `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` 默认 MGF1 用 SHA-1，
 * 而 Node 的 `oaepHash: 'sha256'` 是"摘要与 MGF1 都用 SHA-256" —— 必须显式传
 * [OAEPParameterSpec]，否则服务端解不开。RSA-3072 下明文上限 318 字节，
 * 教务 cookie 串远小于此。
 */
object JwSessionTicket {

    /** 密封用公钥（RSA-3072，X.509 SPKI Base64），与 showwe 服务端私钥配对。 */
    private const val JW_VERIFY_PUBLIC_KEY_SPKI_B64 =
        "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA44L9z5OX7J8/Xz55jIKK3M2hZRM9YxGpu9APP1EyU2g2" +
            "AP5jkqF+/ZY7ezkkVIVQjWWpFAQmcQUoklR/a2OiS1J8fJXMiVPFO5SNrqCjlwcRqy31eVAFyWplqQGjZgGtY7Wy" +
            "bLhpmp0teFVxKGB+KytqKcy49W4cMsVI5aciDq3QNL/daJ0jJAnUefZFWd8VoXnggbzv168oVW/Hi1FvbWSTV8iu" +
            "wN5ym4/3Vts7e4feW5vxeh8OrvMMN63PKON1QWRCoovOD4rxUkehNTs/9gmw3Bxj5TTAYCBmy1+17pckQh22mZty" +
            "eBY1RuGcA8/4t0TijXilt8G5Ggs7oCt2zEVghnnd4X+yw77ZpVgevhUukjZW2oBs+A4IocTNQ+BPWi3fMxWec1v/" +
            "gkhCDusJZWlKoipTiwefAKJfqYICMmZIJch7tiJ1+p36FwYMXcxVF9vpndpYyXTI47YycToaG4kOXhDkkYPo7KyO" +
            "ZFsoBOh54PMEZkXBbh0pFcsxYNrjAgMBAAE="

    /** 教务 cookie 串（`k=v; k2=v2`）→ Base64 密文；空串或异常返回 null（按"没有票据"处理）。 */
    fun seal(cookie: String): String? = seal(cookie, releasePublicKey())

    /** 可注入公钥的重载（单测用）。 */
    internal fun seal(cookie: String, publicKey: PublicKey): String? {
        if (cookie.isBlank()) return null
        return runCatching {
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                publicKey,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            Base64.getEncoder().encodeToString(cipher.doFinal(cookie.toByteArray(Charsets.UTF_8)))
        }.getOrNull()
    }

    fun releasePublicKey(): PublicKey =
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(JW_VERIFY_PUBLIC_KEY_SPKI_B64)))
}
