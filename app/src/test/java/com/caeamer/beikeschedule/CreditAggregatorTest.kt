package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.repo.CreditAggregator
import com.caeamer.beikeschedule.import.parser.GradesParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 学分类别"已完成学分"本地汇总单测——口径与教务网页"学业完成情况"一致
 * （按 kclb 分组、只计已通过课程学分）。fixture 为真实成绩单。
 */
class CreditAggregatorTest {

    private fun grades() = GradesParser.parseGrades(
        requireNotNull(javaClass.classLoader?.getResource("grcjcx-all.json")) { "缺少 fixture" }
            .readText(Charsets.UTF_8),
    )

    @Test
    fun `按类别汇总已通过学分 - 与教务网页数值一致`() {
        val sums = CreditAggregator.sumPassedByCategory(grades())
        // 教务网"学业完成情况"页显示：学科平台 23.5、实验 5.0、基础实习 3.0、专业实习 2.0
        assertEquals(23.5, sums.getValue("学科平台"), 0.001)
        assertEquals(5.0, sums.getValue("实验"), 0.001)
        assertEquals(3.0, sums.getValue("基础实习"), 0.001)
        assertEquals(2.0, sums.getValue("专业实习"), 0.001)
    }

    @Test
    fun `类别行匹配 - 全等或行名以本地名结尾`() {
        val sums = mapOf(
            "通识课程" to 53.0,
            "美育(素质拓展)" to 2.0,
            "专业拓展" to 2.0,
        )
        assertEquals(53.0, CreditAggregator.completedCreditsFor(sums, "通识课程"), 0.001)
        // 网页行名 "素质拓展—美育(素质拓展)" 应匹配本地 "美育(素质拓展)"
        assertEquals(2.0, CreditAggregator.completedCreditsFor(sums, "素质拓展—美育(素质拓展)"), 0.001)
        // 网页行名 "专业拓展-总—专业拓展" 应匹配本地 "专业拓展"
        assertEquals(2.0, CreditAggregator.completedCreditsFor(sums, "专业拓展-总—专业拓展"), 0.001)
        assertEquals(0.0, CreditAggregator.completedCreditsFor(sums, "劳育"), 0.001)
    }
}
