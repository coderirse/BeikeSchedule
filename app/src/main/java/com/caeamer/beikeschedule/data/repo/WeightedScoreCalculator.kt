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
     * @param courses 已收敛的成绩行（[GradeTriple.identity] = [com.caeamer.beikeschedule.data.local.GradeEntity.identityKey]，
     *   另含 xf 学分、zzcj 成绩、kcxz 课程性质）
     * @param excluded 用户手动排除的课程身份键集合
     *
     * 排除用**课程身份**而非下标：下标是"用位置当身份"，一旦上游筛选/排序变化就会
     * 静默排除错的课（此前 ViewModel 用 mapIndexedNotNull 生成下标集合，计算器内部
     * 再对同一列表 filter 一遍，属于隐式契约）。身份也不能用裸 kcdm：kcdm 缺失的行
     * 会共享 "" 这一个键，排除一门等于排除全部空 kcdm 课程。
     */
    fun calculate(courses: List<GradeTriple>, excluded: Set<String> = emptySet()): WeightedResult? {
        val included = courses.filter { c ->
            c.identity !in excluded &&
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

    /** 参与加权的一行：[identity] 是排除口径用的课程身份键（kcdm，缺失时为课程名）。 */
    data class GradeTriple(
        val identity: String,
        val xf: Double,
        val score: Double?,
        val kcxz: String,
    )
}
