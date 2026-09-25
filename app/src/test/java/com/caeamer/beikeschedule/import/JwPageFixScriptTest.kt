package com.caeamer.beikeschedule.import

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * PAGE_FIX_JS 的回归护栏。
 *
 * 踩过的坑：旧脚本第 3 行先置 `__bkPageFixInstalled`、第 10 行才做 appendChild，
 * 而 onPageStarted 注入时文档根常常还没建出来 → appendChild 抛 TypeError 中断，
 * 标志却已经置上 → onPageFinished/500ms/1500ms 三次兜底注入全被挡掉 → 教务页白屏
 * （#app 的 height:100vh 在此 WebView 算出 0 + overflow:hidden）。
 * 这里只锁"顺序"这一条不变式：没拿到文档根就不能置安装标志，且必须能重试。
 */
class JwPageFixScriptTest {

    @Test
    fun `安装标志必须在文档根就绪之后才置位`() {
        val guard = PAGE_FIX_JS.indexOf("if (!r) return false;")
        val install = PAGE_FIX_JS.indexOf("window.__bkPageFixInstalled = true")
        assertTrue("脚本里必须有文档根空值保护", guard > 0)
        assertTrue("必须有安装置位语句", install > 0)
        assertTrue("置位必须晚于空值保护，否则一次注入失败会把兜底注入全部锁死", guard < install)
    }

    @Test
    fun `不再出现无保护的 appendChild 写法`() {
        assertFalse(
            "无保护的 (document.head || document.documentElement).appendChild 正是白屏根因",
            PAGE_FIX_JS.contains("(document.head || document.documentElement).appendChild"),
        )
    }

    @Test
    fun `文档根未就绪时必须能重试`() {
        assertTrue("需要条件重试（setInterval）覆盖根元素迟建的情况", PAGE_FIX_JS.contains("setInterval"))
    }
}
