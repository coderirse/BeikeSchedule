package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.import.parser.GpaInfo

/**
 * 加权成绩计算：只看必修课的数字成绩，等级制（优/良/中/及格）不参与。
 * 公式：Σ(成绩 × 学分) / Σ学分。
 */
object WeightedScoreCalculator {

    data class WeightedResult(
        val score: Double,           // 加权平均分（保留两位小数由调用方处理）
        val totalCredits: Double,    // 纳入计算的学分总数
        val courseCount: Int,        // 纳入计算的课程数
    )

    /**
     * @param courses (xf 学分, zzcj 成绩, kcxz 课程性质) 三元组列表
     * @param excludedIds 用户手动排除的课程下标（相对 courses 列表）
     */
    fun calculate(courses: List<GradeTriple>, excludedIndices: Set<Int> = emptySet()): WeightedResult? {
        val included = courses.filterIndexed { index, c ->
            index !in excludedIndices &&
                c.kcxz == "必修" &&
                c.score != null
        }
        if (included.isEmpty()) return null
        val sumScoreCredits = included.sumOf { it.score!! * it.xf }
        val sumCredits = included.sumOf { it.xf }
        if (sumCredits == 0.0) return null
        return WeightedResult(sumScoreCredits / sumCredits, sumCredits, included.size)
    }

    data class GradeTriple(val xf: Double, val score: Double?, val kcxz: String)
}
