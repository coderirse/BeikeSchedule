package com.caeamer.beikeschedule.import.parser

import com.caeamer.beikeschedule.data.local.ExamEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 考试解析单测。空态 fixture 为真实接口返回；数据行为构造样例（字段名来自列定义 JS）。 */
class ExamsParserTest {

    private fun loadFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "缺少 fixture: $name" }
            .readText(Charsets.UTF_8)

    @Test
    fun `空态 fixture - 返回空列表（真的没有考试）`() {
        assertEquals(emptyList<ExamEntity>(), ExamsParser.parseExams(loadFixture("exams-empty.json"), "2026-20271"))
        assertEquals(emptyList<ExamEntity>(), ExamsParser.parseExams("""{"total":0,"list":[]}""", "2026-20271"))
    }

    /**
     * 调用方对考试表是 clear + insertAll 的覆盖式写入：把"没拿到数据"折叠成空列表，
     * 会连带清空已存考试安排并取消未来提醒，而用户看到的只是"这次没抓到"。
     */
    @Test
    fun `拿不到 list 一律返回 null（不能折叠成没有考试）`() {
        assertNull(ExamsParser.parseExams("", "2026-20271"))
        assertNull(ExamsParser.parseExams("not json", "2026-20271"))
        assertNull(ExamsParser.parseExams("{}", "2026-20271"))
        assertNull(ExamsParser.parseExams("""{"total":0}""", "2026-20271"))
        assertNull(ExamsParser.parseExams("""{"code":500,"msg":"系统异常","content":null}""", "2026-20271"))
        assertNull(ExamsParser.parseExams("<html><body>请重新登录</body></html>", "2026-20271"))
        assertNull(ExamsParser.parseExams("""{"list":"not an array"}""", "2026-20271"))
    }

    @Test
    fun `标准时间描述 - 解析出日期与起止时间`() {
        val json = """
            {"total":1,"list":[{"KCDM":"1060122","KCMC":"概率论与数理统计A","KSSJDMC":"期末考试",
            "KSSJMS":"2027-01-15 08:00~09:50","ZWH":"12","CDXX":"机械楼314","CDDM":"",
            "JKJSBZ":"","KKYXMC":"数理学院"}]}
        """.trimIndent()
        val exams = requireNotNull(ExamsParser.parseExams(json, "2026-20271"))
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
        val exam = requireNotNull(ExamsParser.parseExams(json, "2026-20271")).first()
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
