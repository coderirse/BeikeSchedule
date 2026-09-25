package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.GradeEntity

/**
 * 成绩行的"每门课只留一行"收敛（纯函数，便于单测）。
 *
 * 为什么必须有这一层：教务 `grcjcx` 对补考/重修/刷分**原样返回多行同 kcdm 记录**，
 * 服务端不做覆盖（见 `assets/import/jw_grades.js` 的直通逻辑）。此前只有 GPA 做了去重，
 * 加权成绩、未通过门数、学分类别进度三条路径都直接对原始行求和，导致：
 * - 加权成绩把正考与补考两条的学分都计入分母（同一门 3 学分课算成 6 学分）；
 * - 补考通过后正考的挂科行仍在，"N 门未通过"永不消失、分数永远标红；
 * - 学分类别进度重复计学分，可能超过"毕业总进度"的已修学分。
 * 三条路径现在统一走本函数，口径与 GPA 一致。
 */
object GradeRows {

    /**
     * 每门课（同 kcdm）只保留一行：
     * - 有补考/重修行时，只从这些行里取最高分；
     * - 没有时，从全部行里取最高分。
     *
     * 口径与 `docs/JWXT_API.md` 的"同 kcdm 有补考/重修行只取补考/重修（多行取最高）"一致：
     * 补考/重修行**取代**正考行，而不是与正考行一起取最大值。`bkcx` 的实际取值是
     * `正考` / `补考` / `重修`（见 GpaCalculatorTest 的用例）。
     *
     * **唯一未覆盖的边界**：正考分**高于**补考分时（如正考 58 挂、补考 50 仍挂），
     * 这里取 50 而丢掉 58。这是"补考取代正考"口径的必然结果，也符合教务的实际记法；
     * 但若将来发现学校对"补考未过、最终仍记正考分"有不同处理，需在此调整。
     * 现有行为已由 GradeRowsTest / GpaCalculatorTest 钉住。
     *
     * @param grades 任意成绩行；非数字成绩（等级制）行由调用方先过滤
     */
    /**
     * 每门课（同 kcdm）只保留一行：
     * - 有补考/重修行时，只从这些行里取最高分；
     * - 没有时，从全部行里取最高分。
     * - kcdm 缺失（解析兜底为空串）的行按课程名分组兜底：否则所有空 kcdm 的**不同课程**
     *   会被归为一组只留一行，GPA 学分/门数、学分类别进度静默少算。
     *
     * 口径与 `docs/JWXT_API.md` 的"同 kcdm 有补考/重修行只取补考/重修（多行取最高）"一致：
     * 补考/重修行**取代**正考行，而不是与正考行一起取最大值。`bkcx` 的实际取值是
     * `正考` / `补考` / `重修`（见 GpaCalculatorTest 的用例）。
     *
     * **唯一未覆盖的边界**：正考分**高于**补考分时（如正考 58 挂、补考 50 仍挂），
     * 这里取 50 而丢掉 58。这是"补考取代正考"口径的必然结果，也符合教务的实际记法；
     * 但若将来发现学校对"补考未过、最终仍记正考分"有不同处理，需在此调整。
     * 现有行为已由 GradeRowsTest / GpaCalculatorTest 钉住。
     *
     * 另一既定边界（R4 审查修复）：**等级制已通过的正考行**（"中"/"良"…，numericScore=null）
     * 不被数字挂科的重修行取代——取代后 isPassed 变 false，学分汇总静默少算这门课；
     * 仅当重修分数 ≥60（真通过了）时才让位。
     *
     * @param grades 任意成绩行；非数字成绩（等级制）行由调用方先过滤
     */
    fun bestPerCourse(grades: List<GradeEntity>): List<GradeEntity> =
        grades.groupBy { it.kcdm.ifBlank { it.kcmc } }.map { (_, rows) -> bestRow(rows) }

    private fun bestRow(rows: List<GradeEntity>): GradeEntity {
        val retakes = rows.filter { it.bkcx.isNotBlank() && it.bkcx != "正考" }
        val candidates = if (retakes.isEmpty()) rows else retakes
        val best = candidates.maxByOrNull { it.numericScore ?: Double.NEGATIVE_INFINITY }
            ?: rows.first()
        val passedGradeRow = rows.firstOrNull { it.numericScore == null && it.isPassed }
        if (passedGradeRow != null) {
            val bestNumeric = best.numericScore
            // 数字重修通过（≥60）：正常取代。其余情况（重修挂科 / 最优行本身也是
            // 不在通过集里的等级行）一律保留"已通过"的等级行，别丢学分。
            if (bestNumeric == null || bestNumeric < 60) return passedGradeRow
        }
        return best
    }

    /** 有数字成绩的行（等级制成绩不参与任何加权/GPA 计算）。 */
    fun numericOnly(grades: List<GradeEntity>): List<GradeEntity> =
        grades.filter { it.numericScore != null }
}
