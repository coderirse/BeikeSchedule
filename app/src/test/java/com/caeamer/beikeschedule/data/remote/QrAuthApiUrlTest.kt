package com.caeamer.beikeschedule.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 二维码授权 URL 拼接（纯 JVM）：sid/auth_code 等必须编码，
 * 含 `&`/`=`/空格时不得拆断查询串或污染 retUrl。
 */
class QrAuthApiUrlTest {

    private val target = QrAuthApi.QrTarget(
        sid = "SID1",
        appId = "APP1",
        randToken = "TOK1",
        retUrl = "https://sso.ustb.edu.cn/idp/authCenter/authenticateByLck?thirdPartyAuthCode=microQr&lck=ctx1",
    )

    @Test
    fun `authorizeUrl appends params with ampersand when retUrl has query`() {
        val url = QrAuthApi.authorizeUrl(target, authCode = "CODE1")
        assertEquals(
            "https://sso.ustb.edu.cn/idp/authCenter/authenticateByLck" +
                "?thirdPartyAuthCode=microQr&lck=ctx1" +
                "&appid=APP1&auth_code=CODE1&rand_token=TOK1",
            url,
        )
    }

    @Test
    fun `authorizeUrl uses question mark when retUrl has no query`() {
        val t = target.copy(retUrl = "https://sso.ustb.edu.cn/callback")
        assertEquals(
            "https://sso.ustb.edu.cn/callback?appid=APP1&auth_code=CODE1&rand_token=TOK1",
            QrAuthApi.authorizeUrl(t, "CODE1"),
        )
    }

    @Test
    fun `authorizeUrl encodes authCode containing ampersand and equals`() {
        val url = QrAuthApi.authorizeUrl(target, authCode = "a&b=c d")
        // 编码后不得出现裸的 &auth 之外的额外参数段
        assertEquals(true, url.contains("auth_code=a%26b%3Dc+d") || url.contains("auth_code=a%26b%3Dc%20d"))
        assertEquals(false, url.contains("auth_code=a&b="))
    }

    @Test
    fun `authorizeUrl encodes appId and randToken`() {
        val t = target.copy(appId = "A&1", randToken = "T=1")
        val url = QrAuthApi.authorizeUrl(t, "C")
        assertEquals(true, url.contains("appid=A%261"))
        assertEquals(true, url.contains("rand_token=T%3D1"))
    }

    @Test
    fun `pollStateUrl puts sid in query`() {
        assertEquals(
            "https://sis.ustb.edu.cn/connect/state?sid=SID1",
            QrAuthApi.pollStateUrl("SID1"),
        )
    }

    @Test
    fun `pollStateUrl encodes special chars in sid`() {
        val url = QrAuthApi.pollStateUrl("a&b=c")
        assertEquals("https://sis.ustb.edu.cn/connect/state?sid=a%26b%3Dc", url)
    }

    @Test
    fun `wechatAuthorizeUrl puts sid in query and encodes`() {
        assertEquals(
            "https://sis.ustb.edu.cn/scan/sync?sid=SID1",
            QrAuthApi.wechatAuthorizeUrl("SID1"),
        )
        assertEquals(
            "https://sis.ustb.edu.cn/scan/sync?sid=a%26b%3Dc",
            QrAuthApi.wechatAuthorizeUrl("a&b=c"),
        )
    }
}
