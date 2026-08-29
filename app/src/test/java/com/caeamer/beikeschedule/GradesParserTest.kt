package com.caeamer.beikeschedule.import.parser

import org.junit.Assert.assertEquals
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
