package com.caeamer.beikeschedule.ui.sync

import com.caeamer.beikeschedule.import.isByytHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓取脚本只能在 byyt 本体域运行（脚本内全是相对路径，注入到 SSO/微认证域会 404；
 * 平台层桥按 ustb 任意子域放行，所以"主框架 + byyt"也是结果可信的必要条件）。
 */
class UnifiedSyncHostTest {

    @Test
    fun `byyt main and paths are allowed`() {
        assertTrue(isByytHost("https://byyt.ustb.edu.cn"))
        assertTrue(isByytHost("https://byyt.ustb.edu.cn/authentication/main"))
        assertTrue(isByytHost("https://BYYT.ustb.edu.cn/user/me"))
    }

    @Test
    fun `sso and sis are rejected`() {
        assertFalse(isByytHost("https://sso.ustb.edu.cn/idp/authCenter/authenticateByLck"))
        assertFalse(isByytHost("https://sis.ustb.edu.cn/connect/qrpage"))
    }

    @Test
    fun `blank and non-byyt rejected`() {
        assertFalse(isByytHost(null))
        assertFalse(isByytHost(""))
        assertFalse(isByytHost("https://evil.example/byyt.ustb.edu.cn"))
        assertFalse(isByytHost("not a url"))
    }
}
