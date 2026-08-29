package com.caeamer.beikeschedule.import.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 学业完成情况解析单测。fixture 为 2026-08-29 真实会话抓取（docs/samples/）。 */
class CreditProgressParserTest {

    private fun loadFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "缺少 fixture: $name" }
            .readText(Charsets.UTF_8)

    @Test
    fun `学分类别要求 - 17行且要求学分与教务网页一致`() {
        val categories = CreditProgressParser.parseCategories(loadFixture("xflbyq.json"))
        assertEquals(17, categories.size)

        val byName = categories.associateBy { it.kclbmc }
        assertEquals(67.0, byName.getValue("通识课程").yqxf, 0.001)
        assertEquals(33.0, byName.getValue("学科平台").yqxf, 0.001)
        assertEquals(14.0, byName.getValue("专业核心").yqxf, 0.001)
        assertEquals(2.0, byName.getValue("创新学分").yqxf, 0.001)
        assertEquals("必修", byName.getValue("通识课程").kcxzmc)
    }

    @Test
    fun `毕业总进度 - 要求125_5已修89_5`() {
        val p = CreditProgressParser.parseProgress(loadFixture("bxkqk.json"))
        requireNotNull(p)
        assertEquals(125.5, p.yqxf, 0.001)
        assertEquals(89.5, p.ywcxf, 0.001)
        assertEquals(36.0, p.wwcxf, 0.001)
        assertEquals(58, p.yqms)
        assertEquals(43, p.ywcms)
    }

    @Test
    fun `非法输入 - 返回空`() {
        assertTrue(CreditProgressParser.parseCategories("").isEmpty())
        assertTrue(CreditProgressParser.parseCategories("not json").isEmpty())
        assertNull(CreditProgressParser.parseProgress(""))
        assertNull(CreditProgressParser.parseProgress("not json"))
        // 全 0 要求（无培养方案）→ 过滤为空/返回 null
        assertTrue(CreditProgressParser.parseCategories("""{"content":{"list":[{"kclbmc":"x","yqwcxf":0}]}}""").isEmpty())
        assertNull(CreditProgressParser.parseProgress("""{"content":{"yqmsxf":{"YQXF":0,"YQMS":0}}}"""))
    }
}
