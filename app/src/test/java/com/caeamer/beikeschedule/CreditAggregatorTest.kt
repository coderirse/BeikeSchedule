package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.repo.CreditAggregator
import com.caeamer.beikeschedule.import.parser.GradesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `等级制 通过 计入已通过学分`() {
        // fixture 里 zzcj="通过" 的 3 行：军训(国防公益 2 学分)、
        // 新时代大学生国家安全教育(通识课程 1 学分)、大学生公共安全教育(通识课程 0 学分)。
        // 教务网 getgpa.json 的 HDXF=112.5 / TGKC=54 把这三行算作通过，
        // 漏判会让类别进度少算学分（修复前通识课程是 53.0、国防公益缺失）。
        val sums = CreditAggregator.sumPassedByCategory(grades())
        assertEquals(54.0, sums.getValue("通识课程"), 0.001)
        assertEquals(2.0, sums.getValue("国防公益"), 0.001)

        // 分类合计 = 教务 HDXF 112.5 − 6.0：`工科数学分析II` 的 kclb 是显式 null，
        // 教务没给它课程类别，本地汇总按"无类别"跳过（不能凭空造一个类别）。
        assertEquals(106.5, sums.values.sum(), 0.001)
    }

    @Test
    fun `无课程类别的行不会造出名为 null 的类别`() {
        // kclb 是 JSON null 时，用 jsonPrimitive.content 会得到字面量 "null"，
        // 于是学分进度页会多出一条叫 "null" 的类别（6.0 学分）
        val sums = CreditAggregator.sumPassedByCategory(grades())
        assertFalse("不应存在名为 null 的学分类别", sums.containsKey("null"))
        assertFalse(sums.containsKey(""))
    }

    @Test
    fun `通过 二字判定为已通过且不标红`() {
        val passed = grades().first().copy(zzcj = "通过")
        assertTrue(passed.isPassed)
        assertFalse(passed.isFailed)
    }

    @Test
    fun `类别行匹配 - 全等或行名以本地名结尾`() {
        val sums = mapOf(
            "通识课程" to 54.0,
            "美育(素质拓展)" to 2.0,
            "专业拓展" to 2.0,
        )
        assertEquals(54.0, CreditAggregator.completedCreditsFor(sums, "通识课程"), 0.001)
        // 网页行名 "素质拓展—美育(素质拓展)" 应匹配本地 "美育(素质拓展)"
        assertEquals(2.0, CreditAggregator.completedCreditsFor(sums, "素质拓展—美育(素质拓展)"), 0.001)
        // 网页行名 "专业拓展-总—专业拓展" 应匹配本地 "专业拓展"
        assertEquals(2.0, CreditAggregator.completedCreditsFor(sums, "专业拓展-总—专业拓展"), 0.001)
        assertEquals(0.0, CreditAggregator.completedCreditsFor(sums, "劳育"), 0.001)
    }
}
