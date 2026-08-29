package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.GradeEntity

/**
 * 学分类别"已完成学分"本地汇总——与教务网页"学业完成情况"口径一致：
 * 按 grcjcx.kclb 分组、只计已通过课程的学分之和。
 * 类别名与 queryXflbyq 的行匹配规则：全等，或行名以本地类别名结尾
 * （如 "素质拓展—美育(素质拓展)".endsWith("美育(素质拓展)")）。
 */
object CreditAggregator {

    /** 已通过学分：类别名 → 学分和。 */
    fun sumPassedByCategory(grades: List<GradeEntity>): Map<String, Double> =
        grades.filter { it.isPassed && it.kclb.isNotBlank() }
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
