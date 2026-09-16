package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.GradeEntity

/**
 * 学分类别"已完成学分"本地汇总——与教务网页"学业完成情况"口径一致：
 * 按 grcjcx.kclb 分组、只计已通过课程的学分之和。
 * 类别名与 queryXflbyq 的行匹配规则：全等，或行名以本地类别名结尾
 * （如 "素质拓展—美育(素质拓展)".endsWith("美育(素质拓展)")）。
 *
 * 必须先按 kcdm 收敛（见 [GradeRows.bestPerCourse]）：一门课重修通过后原始行有两行
 * 且都算"通过"，直接求和会把 4 学分课算成 8 学分，类别进度可能超过它上方显示的
 * "毕业总进度 · 已修学分"。
 */
object CreditAggregator {

    /**
     * 已通过学分：类别名 → 学分和。
     *
     * 两步都按 kcdm 收敛：
     * 1. 先 `bestPerCourse` 决定"这门课最终记哪一行"——必须在 `isPassed` 过滤**之前**做。
     *    否则正考 55（挂）与补考 78（过）两行同时在列时，已通过集合里只剩补考行，
     *    但补考行偶尔缺 `xf`（解析成 0.0），按学分取最大就会取到正考行，
     *    于是既算进了未通过的正考分、又拿到它更大的学分——若正考未通过则整门课被漏算。
     * 2. 再对"最终通过的课程"按 kcdm 去重，保证重修不会把 4 学分课算成 8 学分。
     */
    fun sumPassedByCategory(grades: List<GradeEntity>): Map<String, Double> =
        GradeRows.bestPerCourse(grades)
            .filter { it.isPassed && it.kclb.isNotBlank() }
            .distinctBy { it.kcdm }
            .groupBy { it.kclb }
            .mapValues { (_, rows) -> rows.sumOf { it.xf } }

    /** 指定学分类别行的已完成学分：精确匹配优先，其次最长后缀匹配（无匹配返回 0）。 */
    fun completedCreditsFor(localSums: Map<String, Double>, rowName: String): Double {
        localSums[rowName]?.let { return it }
        val best = localSums.keys
            .filter { rowName.endsWith(it) }
            .maxByOrNull { it.length } ?: return 0.0
        return localSums.getValue(best)
    }
}
