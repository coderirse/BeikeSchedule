package com.caeamer.beikeschedule.import.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 考试解析单测。空态 fixture 为真实接口返回；数据行为构造样例（字段名来自列定义 JS）。 */
class ExamsParserTest {

    private fun loadFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "缺少 fixture: $name" }
            .readText(Charsets.UTF_8)

    @Test
    fun `空态 fixture - 返回空列表`() {
        val exams = ExamsParser.parseExams(loadFixture("exams-empty.json"), "2026-20271")
        assertTrue(exams.isEmpty())
    }

    @Test
    fun `非法输入 - 返回空列表`() {
        assertTrue(ExamsParser.parseExams("", "2026-20271").isEmpty())
        assertTrue(ExamsParser.parseExams("not json", "2026-20271").isEmpty())
        assertTrue(ExamsParser.parseExams("""{"total":0}""", "2026-20271").isEmpty())
    }

    @Test
    fun `标准时间描述 - 解析出日期与起止时间`() {
        val json = """
            {"total":1,"list":[{"KCDM":"1060122","KCMC":"概率论与数理统计A","KSSJDMC":"期末考试",
            "KSSJMS":"2027-01-15 08:00~09:50","ZWH":"12","CDXX":"机械楼314","CDDM":"",
            "JKJSBZ":"","KKYXMC":"数理学院"}]}
        """.trimIndent()
        val exams = ExamsParser.parseExams(json, "2026-20271")
        assertEquals(1, exams.size)
        val exam = exams.first()
        assertEquals("2027-01-15", exam.ksrq)
        assertEquals("08:00", exam.kssj)
        assertEquals("09:50", exam.jssj)
        assertEquals("期末考试", exam.kslx)
        assertEquals("12", exam.zwh)
        assertEquals("机械楼314", exam.cdmc)
        assertEquals("2026-20271", exam.xnxq)
        assertTrue(exam.hasDate)
    }

    @Test
    fun `无时间的时间描述 - 日期留空回退原文`() {
        val json = """
            {"total":1,"list":[{"KCMC":"大学物理B","KSSJMS":"第16周 星期三","ZWH":"5",
            "CDXX":"教学楼201","KSSJDMC":"期末考试"}]}
        """.trimIndent()
        val exams = ExamsParser.parseExams(json, "2026-20271")
        val exam = exams.first()
        assertEquals("", exam.ksrq)
        assertEquals("", exam.kssj)
        assertEquals("", exam.jssj)
        assertTrue(!exam.hasDate)
        assertEquals("第16周 星期三", exam.kssjms)
    }

    @Test
    fun `时间解析 - 兼容横杠分隔与单位数月日`() {
        assertEquals(
            Triple("2027-02-03", "14:00", "16:00"),
            ExamsParser.parseExamTime("2027-2-3 14:00-16:00"),
        )
        assertEquals(Triple("", "", ""), ExamsParser.parseExamTime("时间待定"))
    }
}
