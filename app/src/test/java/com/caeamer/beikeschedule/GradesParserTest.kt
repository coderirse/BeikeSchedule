package com.caeamer.beikeschedule.import.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩解析器单测。fixture 为 2026-08-29 用真实登录会话抓取的接口返回，
 * 原始文件见 docs/samples/。
 */
class GradesParserTest {

    private fun loadFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "缺少 fixture: $name" }
            .readText(Charsets.UTF_8)

    @Test
    fun `解析全量成绩 fixture - 条目数与字段正确`() {
        val grades = GradesParser.parseGrades(loadFixture("grcjcx-all.json"))
        assertEquals(54, grades.size)

        val first = grades.first()
        assertEquals("概率论与数理统计A", first.kcmc)
        assertEquals("1060122", first.kcdm)
        assertEquals("2025-2026-2", first.xnxqmc)
        assertEquals("必修", first.kcxz)
        assertEquals(3.0, first.xf, 0.001)
        assertEquals("99", first.zzcj)
        assertTrue(!first.isFailed)
    }

    @Test
    fun `解析全量成绩 fixture - 单科排名与考核方式字段`() {
        val grades = GradesParser.parseGrades(loadFixture("grcjcx-all.json"))
        val first = grades.first()
        // 真实样例：概率论与数理统计A pm=5 zrs=128 khfs=考试
        assertEquals("5", first.pm)
        assertEquals("128", first.zrs)
        assertEquals("考试", first.khfs)
    }

    @Test
    fun `显式 null 字段解析为空串而不是字面量 null`() {
        // 真实 fixture 里 "pm":null 出现 29 次、"khfs":null 8 次、"kclb":null 1 次。
        // JsonNull 本身是 JsonPrimitive 且 content 返回字符串 "null"，
        // 用 content 取值会让 UI 渲染出"排名 null/96"，必须用 contentOrNull。
        val grades = GradesParser.parseGrades(loadFixture("grcjcx-all.json"))

        val nullPm = grades.filter { it.pm.isEmpty() }
        assertEquals(29, nullPm.size)
        assertTrue("不应有任何 pm 被解析成字面量 null", grades.none { it.pm == "null" })
        assertTrue(grades.none { it.zrs == "null" })
        assertTrue(grades.none { it.khfs == "null" })
        assertTrue(grades.none { it.kclb == "null" })
        assertTrue(grades.none { it.kcxz == "null" })

        // 有排名但无考核方式的行：以前会显示"排名 5/128"附近多出一个"考核方式 null"
        assertEquals(8, grades.count { it.khfs.isEmpty() })
        assertEquals(1, grades.count { it.kclb.isEmpty() })
    }

    @Test
    fun `JSON null 字段的工具函数行为`() {
        val grades = GradesParser.parseGrades(
            """{"content":{"list":[{"kcdm":"x","kcmc":"课","zzcj":"90","pm":null,"kclb":null}]}}""",
        )
        assertEquals(1, grades.size)
        assertEquals("", grades[0].pm)
        assertEquals("", grades[0].kclb)
    }

    @Test
    fun `解析全量成绩 fixture - 学期分组覆盖多个学期`() {
        val grades = GradesParser.parseGrades(loadFixture("grcjcx-all.json"))
        val semesters = grades.map { it.xnxqmc }.distinct()
        // 本学期（2026-2027-1）成绩尚未发布，fixture 覆盖此前 5 个学期
        assertEquals(5, semesters.size)
        assertTrue(semesters.contains("2025-2026-2"))
        assertTrue(semesters.contains("2024-2025-1"))
    }

    @Test
    fun `不及格判定 - 低于60标红 等级制不标红`() {
        val grades = GradesParser.parseGrades(loadFixture("grcjcx-all.json"))
        // fixture 全部通过；构造用例验证判定逻辑
        val g = grades.first().copy(zzcj = "59")
        assertTrue(g.isFailed)
        assertTrue(grades.first().copy(zzcj = "60").let { !it.isFailed })
        assertTrue(grades.first().copy(zzcj = "优").let { !it.isFailed })
    }

    @Test
    fun `解析GPA fixture - 字段映射正确`() {
        val gpa = GradesParser.parseGpa(loadFixture("getgpa.json"))
        requireNotNull(gpa)
        assertEquals(4.22, gpa.gpa, 0.001)
        assertEquals(112.5, gpa.earnedCredits, 0.001)
        assertEquals(54, gpa.passedCourses)
        assertEquals(7, gpa.rank)
        assertEquals(166, gpa.totalStudents)
    }

    @Test
    fun `解析GPA - 缺少专业排名时仍返回绩点`() {
        // PM/ZRS 在学期初排名未生成、未排名专业、转专业首学期都可能缺失。
        // 此前它们是必需字段，缺失会让整个 GpaInfo 变成 null，GPA 卡片整块显示"—"，
        // 尽管 BL（平均学分绩）与 HDXF（已获学分）明明有值。
        val gpa = GradesParser.parseGpa("""{"BL":3.85,"HDXF":96.0}""")
        requireNotNull(gpa)
        assertEquals(3.85, gpa.gpa, 0.001)
        assertEquals(96.0, gpa.earnedCredits, 0.001)
        assertNull(gpa.rank)
        assertNull(gpa.totalStudents)
        assertFalse("无排名时 hasRank 必须为 false", gpa.hasRank)
    }

    @Test
    fun `解析GPA - 排名总人数为0时视为无排名`() {
        val gpa = GradesParser.parseGpa("""{"BL":3.85,"HDXF":96.0,"TGKC":20,"PM":0,"ZRS":0}""")
        requireNotNull(gpa)
        assertFalse("总人数为 0 不应渲染成 0/0", gpa.hasRank)
    }

    @Test
    fun `解析GPA - 缺少 BL 或 HDXF 仍返回 null`() {
        // 这两个是展示 GPA 的必要信息，缺任一都无法呈现有效内容
        assertNull(GradesParser.parseGpa("""{"HDXF":96.0,"PM":1,"ZRS":100}"""))
        assertNull(GradesParser.parseGpa("""{"BL":3.85,"PM":1,"ZRS":100}"""))
    }

    @Test
    fun `数字成绩 - 非有限值与越界值不参与计算`() {
        // "NaN"/"Infinity" 能被 toDoubleOrNull 解析成功，但 NaN 的 <60 与 >=60 同时为 false，
        // 会形成 isFailed/isPassed 双 false 的静默第三态并污染求和。
        val base = GradesParser.parseGrades(loadFixture("grcjcx-all.json")).first()
        assertNull(base.copy(zzcj = "NaN").numericScore)
        assertNull(base.copy(zzcj = "Infinity").numericScore)
        assertNull(base.copy(zzcj = "-Infinity").numericScore)
        assertNull(base.copy(zzcj = "999").numericScore)
        assertEquals(88.5, base.copy(zzcj = "88.5").numericScore!!, 0.001)
        assertEquals(0.0, base.copy(zzcj = "0").numericScore!!, 0.001)
    }

    @Test
    fun `解析GPA - 非法输入返回 null`() {
        assertNull(GradesParser.parseGpa("not json"))
        assertNull(GradesParser.parseGpa("""{"msg":"error"}"""))
        assertTrue(GradesParser.parseGrades("not json").isEmpty())
        assertTrue(GradesParser.parseGrades("""{"content":{"list":[]}}""").isEmpty())
    }

    @Test
    fun `版本号比较 - 逐段数字比较`() {
        assertEquals(0, GradesParser.compareVersions("1.0.5", "v1.0.5"))
        assertTrue(GradesParser.compareVersions("1.0.6", "1.0.5") > 0)
        assertTrue(GradesParser.compareVersions("1.0", "1.0.0") == 0)
        assertTrue(GradesParser.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(GradesParser.compareVersions("0.9.9", "1.0.0") < 0)
    }
}
