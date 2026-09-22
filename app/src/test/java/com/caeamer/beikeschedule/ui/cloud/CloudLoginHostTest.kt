package com.caeamer.beikeschedule.ui.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云登录身份脚本只能在 byyt 主域注入，否则相对路径 /user/me 会 404。 */
class CloudLoginHostTest {

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
