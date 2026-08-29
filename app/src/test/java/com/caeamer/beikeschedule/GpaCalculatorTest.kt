package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.repo.GpaCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 4.0 制 GPA 本地算法单测（换算表来自学校权威说明，口径经用户确认）。 */
class GpaCalculatorTest {

    private fun grade(
        kcdm: String,
        zzcj: String,
        xf: Double = 3.0,
        bkcx: String = "正考",
    ) = GradeEntity(
        kcdm = kcdm, kcmc = kcdm, xnxq = "2025-20262", xnxqmc = "2025-2026-2",
        kcxz = "必修", kclb = "通识课程", xf = xf, zzcj = zzcj, bkcx = bkcx, yxmc = "", sffx = false,
    )

    @Test
    fun `绩点换算表 - 各分数段边界`() {
        assertEquals(4.0, GpaCalculator.gradePoint(100.0), 0.0)
        assertEquals(4.0, GpaCalculator.gradePoint(90.0), 0.0)
        assertEquals(3.7, GpaCalculator.gradePoint(89.0), 0.0)
        assertEquals(3.7, GpaCalculator.gradePoint(85.0), 0.0)
        assertEquals(3.4, GpaCalculator.gradePoint(84.0), 0.0)
        assertEquals(3.4, GpaCalculator.gradePoint(80.0), 0.0)
        assertEquals(3.0, GpaCalculator.gradePoint(79.0), 0.0)
        assertEquals(3.0, GpaCalculator.gradePoint(75.0), 0.0)
        assertEquals(2.4, GpaCalculator.gradePoint(74.0), 0.0)
        assertEquals(2.4, GpaCalculator.gradePoint(70.0), 0.0)
        assertEquals(2.0, GpaCalculator.gradePoint(69.0), 0.0)
        assertEquals(2.0, GpaCalculator.gradePoint(65.0), 0.0)
        assertEquals(1.0, GpaCalculator.gradePoint(64.0), 0.0)
        assertEquals(1.0, GpaCalculator.gradePoint(60.0), 0.0)
        assertEquals(0.0, GpaCalculator.gradePoint(59.0), 0.0)
        assertEquals(0.0, GpaCalculator.gradePoint(0.0), 0.0)
    }

    @Test
    fun `学分加权 - 高学分课程权重大`() {
        // (90→4.0)×4 + (60→1.0)×2 = 18 / 6 = 3.0
        val gpa = GpaCalculator.calculate(listOf(grade("A", "90", 4.0), grade("B", "60", 2.0)))
        assertEquals(3.0, gpa!!.gpa, 0.001)
        assertEquals(6.0, gpa.credits, 0.001)
        assertEquals(2, gpa.courseCount)
    }

    @Test
    fun `等级制课程 - 排除不参与`() {
        val gpa = GpaCalculator.calculate(
            listOf(grade("A", "优"), grade("B", "良", 2.0), grade("C", "90", 3.0)),
        )
        assertEquals(4.0, gpa!!.gpa, 0.001)
        assertEquals(3.0, gpa.credits, 0.001)
        assertEquals(1, gpa.courseCount)
    }

    @Test
    fun `补考覆盖 - 有补考行取补考成绩`() {
        // 正考 55（挂） + 补考 75（过）→ 只取补考 75→3.0
        val gpa = GpaCalculator.calculate(
            listOf(grade("A", "55", 3.0), grade("A", "75", 3.0, bkcx = "补考")),
        )
        assertEquals(3.0, gpa!!.gpa, 0.001)
        assertEquals(3.0, gpa.credits, 0.001)
        assertEquals(1, gpa.courseCount)
    }

    @Test
    fun `刷分重修 - 非正考行覆盖正考取最高`() {
        // 正考 70 + 重修 88 → 取 88→3.7
        val gpa = GpaCalculator.calculate(
            listOf(grade("A", "70", 2.0), grade("A", "88", 2.0, bkcx = "重修")),
        )
        assertEquals(3.7, gpa!!.gpa, 0.001)
        assertEquals(1, gpa.courseCount)
    }

    @Test
    fun `挂科课程 - 计入且绩点为0`() {
        // (90→4.0)×3 + (50→0)×3 = 12 / 6 = 2.0
        val gpa = GpaCalculator.calculate(listOf(grade("A", "90", 3.0), grade("B", "50", 3.0)))
        assertEquals(2.0, gpa!!.gpa, 0.001)
        assertEquals(2, gpa.courseCount)
    }

    @Test
    fun `无有效数据 - 返回 null`() {
        assertNull(GpaCalculator.calculate(emptyList()))
        assertNull(GpaCalculator.calculate(listOf(grade("A", "优"), grade("B", "良"))))
        // 学分全为 0 → 无法计算
        assertNull(GpaCalculator.calculate(listOf(grade("A", "90", 0.0))))
    }
}
