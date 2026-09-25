package com.caeamer.beikeschedule.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 微认证二维码会话提取（纯 JVM）：qrpage 带参数、qrimg 带 sid，按序配对；
 * 非 sis 域请求忽略；只保留最近若干个会话。
 */
class QrTargetTrackerTest {

    private val qrpage =
        "https://sis.ustb.edu.cn/connect/qrpage?appid=APP1&return_url=https%3A%2F%2Fsso.ustb.edu.cn%2Fidp%2FauthCenter%2FauthenticateByLck%3FthirdPartyAuthCode%3DmicroQr%26lck%3Dctx1&rand_token=TOK1&embed_flag=1"

    @Test
    fun `qrimg after qrpage produces target`() {
        val tracker = QrTargetTracker()
        assertEquals(false, tracker.onRequest(qrpage))
        assertEquals(true, tracker.onRequest("https://sis.ustb.edu.cn/connect/qrimg?sid=SID1"))

        assertEquals("SID1", tracker.latestSid)
        val target = tracker.snapshot().single()
        assertEquals("SID1", target.sid)
        assertEquals("APP1", target.appId)
        assertEquals("TOK1", target.randToken)
        assertEquals(
            "https://sso.ustb.edu.cn/idp/authCenter/authenticateByLck?thirdPartyAuthCode=microQr&lck=ctx1",
            target.retUrl,
        )
    }

    @Test
    fun `qrimg without prior qrpage is ignored`() {
        val tracker = QrTargetTracker()
        assertEquals(false, tracker.onRequest("https://sis.ustb.edu.cn/connect/qrimg?sid=SID9"))
        assertNull(tracker.latestSid)
    }

    @Test
    fun `other hosts and paths ignored`() {
        val tracker = QrTargetTracker()
        tracker.onRequest(qrpage)
        assertEquals(false, tracker.onRequest("https://sso.ustb.edu.cn/connect/qrimg?sid=SIDX"))
        assertEquals(false, tracker.onRequest("https://sis.ustb.edu.cn/connect/other?sid=SIDY"))
        assertEquals(null, tracker.latestSid)
    }

    @Test
    fun `keeps only recent targets`() {
        val tracker = QrTargetTracker(maxTargets = 2)
        tracker.onRequest(qrpage)
        tracker.onRequest("https://sis.ustb.edu.cn/connect/qrimg?sid=A")
        tracker.onRequest("https://sis.ustb.edu.cn/connect/qrimg?sid=B")
        tracker.onRequest("https://sis.ustb.edu.cn/connect/qrimg?sid=C")
        assertEquals(listOf("B", "C"), tracker.snapshot().map { it.sid })
        assertEquals("C", tracker.latestSid)
    }

    @Test
    fun `authorize url matches page logic`() {
        val target = QrAuthApi.QrTarget(
            sid = "S",
            appId = "APP1",
            randToken = "TOK1",
            retUrl = "https://sso.ustb.edu.cn/idp/authCenter/authenticateByLck?thirdPartyAuthCode=microQr&lck=ctx1",
        )
        assertEquals(
            "https://sso.ustb.edu.cn/idp/authCenter/authenticateByLck?thirdPartyAuthCode=microQr&lck=ctx1" +
                "&appid=APP1&auth_code=CODE1&rand_token=TOK1",
            QrAuthApi.authorizeUrl(target, "CODE1"),
        )
    }

    @Test
    fun `wechat authorize url`() {
        assertEquals(
            "https://sis.ustb.edu.cn/scan/sync?sid=ABC",
            QrAuthApi.wechatAuthorizeUrl("ABC"),
        )
    }
}
