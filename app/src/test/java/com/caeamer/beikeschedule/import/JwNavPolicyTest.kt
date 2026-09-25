package com.caeamer.beikeschedule.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航策略回归护栏。
 *
 * 踩过的坑：微认证授权成功后 SSO 的回调 302 指向 http://sso.ustb.edu.cn/idp/thirdAuth/...，
 * 旧逻辑把"非 https"一律拒绝且不交接，这次跳转被静默取消（连 onReceivedError 都没有），
 * 云登录永久卡在"授权成功，正在进入教务系统…"。现口径：站内按 host 白名单收口，
 * http 与 https 同等放行；站外一律交系统浏览器；无 host / 非 http(s) 仍拒绝。
 */
class JwNavPolicyTest {

    @Test
    fun `教务站内 http 与 https 同等放行`() {
        assertEquals(JwNavPolicy.ALLOW_IN_WEBVIEW, jwNavPolicy("https", "byyt.ustb.edu.cn"))
        assertEquals(JwNavPolicy.ALLOW_IN_WEBVIEW, jwNavPolicy("https", "sso.ustb.edu.cn"))
        assertEquals(JwNavPolicy.ALLOW_IN_WEBVIEW, jwNavPolicy("https", "SIS.USTB.EDU.CN"))
        // SSO 回调 302 指向的正是这条 http 地址
        assertEquals(JwNavPolicy.ALLOW_IN_WEBVIEW, jwNavPolicy("http", "sso.ustb.edu.cn"))
        assertEquals(JwNavPolicy.ALLOW_IN_WEBVIEW, jwNavPolicy("http", "byyt.ustb.edu.cn"))
    }

    @Test
    fun `站外 http 与 https 都交系统浏览器`() {
        assertEquals(JwNavPolicy.EXTERNAL_BROWSER, jwNavPolicy("https", "evil.example"))
        assertEquals(JwNavPolicy.EXTERNAL_BROWSER, jwNavPolicy("http", "evil.example"))
        assertEquals(JwNavPolicy.EXTERNAL_BROWSER, jwNavPolicy("https", "evilustb.edu.cn"))
        assertEquals(JwNavPolicy.EXTERNAL_BROWSER, jwNavPolicy("http", "evilustb.edu.cn"))
    }

    @Test
    fun `无 host 与异常 scheme 依然拒绝`() {
        assertEquals(JwNavPolicy.BLOCK, jwNavPolicy("weixin", null))
        assertEquals(JwNavPolicy.BLOCK, jwNavPolicy("file", null))
        assertEquals(JwNavPolicy.BLOCK, jwNavPolicy("blob", null))
        assertEquals(JwNavPolicy.BLOCK, jwNavPolicy(null, null))
        assertEquals(JwNavPolicy.BLOCK, jwNavPolicy("https", null))
        assertEquals(JwNavPolicy.BLOCK, jwNavPolicy("http", null))
    }

    @Test
    fun `站内 scheme 判定只认 http 与 https`() {
        assertTrue(isHttpScheme("http"))
        assertTrue(isHttpScheme("https"))
        assertFalse(isHttpScheme("HTTP"))
        assertFalse(isHttpScheme("file"))
        assertFalse(isHttpScheme(null))
    }
}
