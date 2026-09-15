package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.import.parser.GradesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩行收敛（每门课只留一行）单测。
 *
 * **这个文件的缺失正是三个缺陷能同时存活的原因**：真实 fixture `grcjcx-all.json` 的
 * 54 行成绩全部是"正考"、54 个不同 kcdm，所以"同一门课多行"这一情形在原有测试里
 * 完全不存在——加权成绩重复计学分、"N 门未通过"永不消失、学分类别重复计学分
 * 三条 bug 在旧测试套件里原理上不可见。
 *
 * 本文件用**显式构造的多行数据**补齐这个盲区，而非依赖 fixture 的分布。
 */
class GradeRowsTest {

    private fun grade(
        kcdm: String,
        zzcj: String,
        xf: Double = 3.0,
        bkcx: String = "正考",
        kclb: String = "学科平台",
    ) = GradeEntity(
        kcdm = kcdm, kcmc = kcdm, xnxq = "2025-20262", xnxqmc = "2025-2026-2",
        kcxz = "必修", kclb = kclb, xf = xf, zzcj = zzcj, bkcx = bkcx, yxmc = "", sffx = false,
    )

    @Test
    fun `同一门课多行只留一行`() {
        val rows = listOf(
            grade("A", "55", bkcx = "正考"),
            grade("A", "75", bkcx = "补考"),
            grade("B", "90"),
        )
        val best = GradeRows.bestPerCourse(rows)
        assertEquals(2, best.size)
        assertEquals(setOf("A", "B"), best.map { it.kcdm }.toSet())
    }

    @Test
    fun `有补考行时取补考行而非正考行`() {
        // 教务的记法是补考/重修"取代"正考。正考 55 挂 + 补考 75 过 → 记 75。
        val best = GradeRows.bestPerCourse(listOf(grade("A", "55"), grade("A", "75", bkcx = "补考")))
        assertEquals(1, best.size)
        assertEquals(75.0, best.single().numericScore!!, 0.001)
        assertEquals("补考", best.single().bkcx)
    }

    @Test
    fun `多条非正考行取其中最高分`() {
        val best = GradeRows.bestPerCourse(
            listOf(
                grade("A", "55", bkcx = "正考"),
                grade("A", "62", bkcx = "补考"),
                grade("A", "80", bkcx = "重修"),
            ),
        )
        assertEquals(80.0, best.single().numericScore!!, 0.001)
    }

    @Test
    fun `无补考行时取全部行中的最高分`() {
        val best = GradeRows.bestPerCourse(listOf(grade("A", "70"), grade("A", "88")))
        assertEquals(88.0, best.single().numericScore!!, 0.001)
    }

    @Test
    fun `补考分低于正考时以补考分为准 - 固化现有口径`() {
        // 唯一未覆盖的边界：正考 58 挂、补考 50 仍挂。按"补考取代正考"的口径记 50。
        // 若将来确认学校对"补考未过仍记正考分"有不同处理，改这里即可（会立刻失败提醒）。
        val best = GradeRows.bestPerCourse(
            listOf(grade("A", "58", bkcx = "正考"), grade("A", "50", bkcx = "补考")),
        )
        assertEquals(50.0, best.single().numericScore!!, 0.001)
        assertNotEquals(58.0, best.single().numericScore!!, 0.001)
    }

    @Test
    fun `等级制成绩行不参与数字比较`() {
        // 等级制行 numericScore 为 null，取最大值时必须当负无穷处理，
        // 不能因为 maxByOrNull 拿到 null 而抛异常或选错行。
        val best = GradeRows.bestPerCourse(listOf(grade("A", "优"), grade("A", "72", bkcx = "补考")))
        assertEquals(72.0, best.single().numericScore!!, 0.001)
    }

    @Test
    fun `不同课程各自独立收敛`() {
        val best = GradeRows.bestPerCourse(
            listOf(
                grade("A", "55"), grade("A", "75", bkcx = "补考"),
                grade("B", "91"), grade("B", "85"),
                grade("C", "60"),
            ),
        )
        assertEquals(3, best.size)
        assertEquals(75.0, best.first { it.kcdm == "A" }.numericScore!!, 0.001)
        assertEquals(91.0, best.first { it.kcdm == "B" }.numericScore!!, 0.001)
        assertEquals(60.0, best.first { it.kcdm == "C" }.numericScore!!, 0.001)
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(GradeRows.bestPerCourse(emptyList()).isEmpty())
    }

    @Test
    fun `真实 fixture 上收敛结果与原始行数一致`() {
        // fixture 里 54 行 = 54 个不同 kcdm（无补考/重修），所以收敛后行数不变。
        // 这条断言同时守住"收敛不能误伤正常成绩单"。
        val grades = GradesParser.parseGrades(
            requireNotNull(javaClass.classLoader?.getResource("grcjcx-all.json")) { "缺少 fixture" }
                .readText(Charsets.UTF_8),
        )
        assertEquals(54, grades.size)
        assertEquals(54, GradeRows.bestPerCourse(grades).size)
    }

    @Test
    fun `numericOnly 过滤等级制行`() {
        val rows = listOf(grade("A", "88"), grade("B", "优"), grade("C", "通过"))
        assertEquals(listOf("A"), GradeRows.numericOnly(rows).map { it.kcdm })
    }
}
