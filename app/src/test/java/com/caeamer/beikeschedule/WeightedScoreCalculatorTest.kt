package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.repo.WeightedScoreCalculator.GradeTriple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 加权成绩计算单测（只看必修、等级制排除、按课程代码收敛）。
 *
 * 输入的 GradeTriple 由 `GradeRows.bestPerCourse` 收敛而来——**同一门课不会出现两行**。
 * 这一点很关键：此前计算器直接吃原始成绩行，补考/重修会让同一门课的学分被计入分母两次。
 */
class WeightedScoreCalculatorTest {

    private fun triple(
        kcdm: String,
        xf: Double,
        score: Double?,
        kcxz: String = "必修",
    ) = GradeTriple(kcdm = kcdm, xf = xf, score = score, kcxz = kcxz)

    @Test
    fun `基本加权 - 必修课按学分加权平均`() {
        // 概率论 3学分 99分 + 体育 1学分 88分 → (3×99 + 1×88)/4 = 96.25
        val result = WeightedScoreCalculator.calculate(
            listOf(
                triple("A", 3.0, 99.0),
                triple("B", 1.0, 88.0),
            ),
        )
        requireNotNull(result)
        assertEquals(96.25, result.score, 0.01)
        assertEquals(4.0, result.totalCredits, 0.001)
        assertEquals(2, result.courseCount)
    }

    @Test
    fun `只看必修 - 任选课不参与`() {
        val result = WeightedScoreCalculator.calculate(
            listOf(
                triple("A", 3.0, 99.0),
                triple("B", 2.0, 90.0, kcxz = "任选"),  // 不参与
            ),
        )
        requireNotNull(result)
        assertEquals(99.0, result.score, 0.01) // 只有必修一门
        assertEquals(1, result.courseCount)
    }

    @Test
    fun `等级制课程不参与计算`() {
        val result = WeightedScoreCalculator.calculate(
            listOf(
                triple("A", 3.0, 99.0),
                triple("B", 2.0, null), // 等级制，无数字分
            ),
        )
        requireNotNull(result)
        assertEquals(99.0, result.score, 0.01)
        assertEquals(3.0, result.totalCredits, 0.001)
    }

    @Test
    fun `按课程代码排除生效`() {
        // 排除机制从"列表下标"改为"课程代码"：下标是拿位置当身份，
        // 上游筛选/排序一变就会静默排除错的课。
        val result = WeightedScoreCalculator.calculate(
            listOf(
                triple("A", 3.0, 99.0),
                triple("B", 1.0, 88.0),
            ),
            excludedKcdm = setOf("A"),
        )
        requireNotNull(result)
        assertEquals(88.0, result.score, 0.01)
        assertEquals(1, result.courseCount)
    }

    @Test
    fun `排除不存在的课程代码不影响结果`() {
        val result = WeightedScoreCalculator.calculate(
            listOf(triple("A", 3.0, 99.0)),
            excludedKcdm = setOf("NOT_EXIST"),
        )
        requireNotNull(result)
        assertEquals(99.0, result.score, 0.01)
    }

    @Test
    fun `零学分与负学分课程不参与`() {
        // 0 学分课不影响加权结果，但会把"纳入 N 门"虚增；
        // 负学分是服务端脏数据，计入会让分母被抵消。
        val result = WeightedScoreCalculator.calculate(
            listOf(
                triple("A", 3.0, 90.0),
                triple("ZERO", 0.0, 100.0),
                triple("NEG", -3.0, 100.0),
            ),
        )
        requireNotNull(result)
        assertEquals(90.0, result.score, 0.01)
        assertEquals(3.0, result.totalCredits, 0.001)
        assertEquals(1, result.courseCount)
    }

    @Test
    fun `无可计算课程返回 null`() {
        assertNull(WeightedScoreCalculator.calculate(emptyList()))
        assertNull(WeightedScoreCalculator.calculate(listOf(triple("A", 1.0, 88.0, kcxz = "任选"))))
        // 只剩 0 学分课 → 无有效分母
        assertNull(WeightedScoreCalculator.calculate(listOf(triple("A", 0.0, 88.0))))
    }

    @Test
    fun `非有限成绩不参与`() {
        val result = WeightedScoreCalculator.calculate(
            listOf(triple("A", 3.0, 90.0), triple("B", 3.0, Double.NaN)),
        )
        requireNotNull(result)
        assertEquals(90.0, result.score, 0.01)
        assertEquals(1, result.courseCount)
    }
}
