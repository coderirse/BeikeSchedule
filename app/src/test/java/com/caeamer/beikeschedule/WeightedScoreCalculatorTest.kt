package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.repo.WeightedScoreCalculator.GradeTriple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 加权成绩计算单测（只看必修、等级制排除）。 */
class WeightedScoreCalculatorTest {

    @Test
    fun `基本加权 - 必修课按学分加权平均`() {
        // 概率论 3学分 99分 + 体育 1学分 88分 → (3×99 + 1×88)/4 = 96.25
        val result = WeightedScoreCalculator.calculate(
            listOf(
                GradeTriple(xf = 3.0, score = 99.0, kcxz = "必修"),
                GradeTriple(xf = 1.0, score = 88.0, kcxz = "必修"),
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
                GradeTriple(xf = 3.0, score = 99.0, kcxz = "必修"),
                GradeTriple(xf = 2.0, score = 90.0, kcxz = "任选"),  // 不参与
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
                GradeTriple(xf = 3.0, score = 99.0, kcxz = "必修"),
                GradeTriple(xf = 2.0, score = null, kcxz = "必修"), // 等级制，无数字分
            ),
        )
        requireNotNull(result)
        assertEquals(99.0, result.score, 0.01)
        assertEquals(3.0, result.totalCredits, 0.001)
    }

    @Test
    fun `手动排除课程生效`() {
        val result = WeightedScoreCalculator.calculate(
            listOf(
                GradeTriple(xf = 3.0, score = 99.0, kcxz = "必修"),  // index 0 排除
                GradeTriple(xf = 1.0, score = 88.0, kcxz = "必修"),
            ),
            excludedIndices = setOf(0),
        )
        requireNotNull(result)
        assertEquals(88.0, result.score, 0.01)
        assertEquals(1, result.courseCount)
    }

    @Test
    fun `无可计算课程返回 null`() {
        assertNull(WeightedScoreCalculator.calculate(emptyList()))
        assertNull(WeightedScoreCalculator.calculate(listOf(GradeTriple(1.0, 88.0, "任选"))))
    }
}
