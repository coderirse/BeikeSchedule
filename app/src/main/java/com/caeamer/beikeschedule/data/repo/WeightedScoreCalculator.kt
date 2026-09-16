package com.caeamer.beikeschedule.data.repo

/**
 * 加权成绩计算：只看必修课的数字成绩，等级制（优/良/中/及格）不参与。
 * 公式：Σ(成绩 × 学分) / Σ学分。
 *
 * 输入必须是**已按课程代码收敛过的行**（见 [GradeRows.bestPerCourse]）。
 * 此前调用方直接传原始成绩行，补考/重修会产生两行同 kcdm，导致同一门课的学分
 * 被计入分母两次（3 学分课算成 6 学分），而 GPA 侧已去重，两个口径互相矛盾。
 */
object WeightedScoreCalculator {

    data class WeightedResult(
        val score: Double,           // 加权平均分（保留两位小数由调用方处理）
        val totalCredits: Double,    // 纳入计算的学分总数
        val courseCount: Int,        // 纳入计算的课程数
    )

    /**
     * @param courses 已按 kcdm 收敛的成绩行 (kcdm, xf 学分, zzcj 成绩, kcxz 课程性质)
     * @param excludedKcdm 用户手动排除的课程代码集合
     *
     * 排除用**课程代码**而非下标：下标是"用位置当身份"，一旦上游筛选/排序变化就会
     * 静默排除错的课（此前 ViewModel 用 mapIndexedNotNull 生成下标集合，计算器内部
     * 再对同一列表 filter 一遍，属于隐式契约）。
     */
    fun calculate(courses: List<GradeTriple>, excludedKcdm: Set<String> = emptySet()): WeightedResult? {
        val included = courses.filter { c ->
            c.kcdm !in excludedKcdm &&
                c.kcxz == "必修" &&
                c.score != null &&
                c.score.isFinite() &&
                c.xf > 0.0
        }
        if (included.isEmpty()) return null
        // 到此处 score 必非 null；用局部变量取值让智能转换生效，避免 !! 依赖非局部不变量
        val scored = included.mapNotNull { c -> c.score?.let { c to it } }
        val sumScoreCredits = scored.sumOf { (c, score) -> score * c.xf }
        val sumCredits = scored.sumOf { (c, _) -> c.xf }
        if (sumCredits <= 0.0 || !sumCredits.isFinite()) return null
        val score = sumScoreCredits / sumCredits
        if (!score.isFinite()) return null
        return WeightedResult(score, sumCredits, scored.size)
    }

    data class GradeTriple(
        val kcdm: String,
        val xf: Double,
        val score: Double?,
        val kcxz: String,
    )
}
