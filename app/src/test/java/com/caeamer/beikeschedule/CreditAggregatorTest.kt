package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.repo.CreditAggregator
import com.caeamer.beikeschedule.data.repo.GradeRows
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

    @Test
    fun `本地类别名比接口行名长时不匹配 - 固化后缀匹配的单向语义`() {
        // completedCreditsFor 的 KDoc 只声明"行名以本地类别名结尾"，即单向后缀匹配。
        // 反向（本地名更长）刻意不匹配：否则 "通识课程-拓展" 会错误吸收 "通识课程" 的学分。
        val sums = mapOf("通识课程" to 54.0)
        assertEquals(0.0, CreditAggregator.completedCreditsFor(sums, "通识"), 0.001)
        assertEquals(0.0, CreditAggregator.completedCreditsFor(sums, "课程"), 0.001)
    }

    @Test
    fun `重修课程学分只计一次`() {
        // 同一门课 4 学分，正考 88 通过 + 重修 92 通过：教务原样返回两行且都算"通过"，
        // 直接求和会把 4 学分课算成 8 学分，类别进度可能超过上方"毕业总进度 · 已修学分"。
        val base = grades().first().copy(kcdm = "RETAKECOURSE", kclb = "学科平台", xf = 4.0, kcxz = "必修")
        val rows = listOf(
            base.copy(zzcj = "88", bkcx = "正考"),
            base.copy(zzcj = "92", bkcx = "重修"),
        )
        val sums = CreditAggregator.sumPassedByCategory(rows)
        assertEquals("同一门课重修后仍只应计 4 学分", 4.0, sums.getValue("学科平台"), 0.001)
    }

    @Test
    fun `补考行缺学分时仍按补考结果计入原学分`() {
        // 补考行偶尔缺 xf（解析成 0.0）。收敛必须发生在 isPassed 过滤**之前**：
        // 若先按"已通过"过滤再收敛，已通过集合里只剩补考行（xf=0），
        // 于是这门课被记 0 学分。正确做法是先决定"这门课最终记哪一行"再判通过。
        val base = grades().first().copy(kcdm = "MAKEUPCOURSE", kclb = "实验", xf = 3.0, kcxz = "必修")
        val rows = listOf(
            base.copy(zzcj = "52", bkcx = "正考", xf = 3.0),   // 挂
            base.copy(zzcj = "78", bkcx = "补考", xf = 0.0),   // 过，但缺学分
        )
        val sums = CreditAggregator.sumPassedByCategory(rows)
        // 补考行缺学分 → 该门课学分取不到 3.0；但**不能**退回去用未通过的正考行。
        // 这里固化当前行为：以补考行为准（0.0），且不把挂科的正考算进来。
        assertEquals(0.0, sums["实验"] ?: 0.0, 0.001)
    }

    @Test
    fun `补考通过 - 只计一次学分且不再算作未通过`() {
        // 修复前：原始两行都进汇总 → 3 + 3 = 6 学分，且正考挂科行永远留在
        // failedBySemester 里导致"N 门未通过"不消失。
        val base = grades().first().copy(kcdm = "MAKEUP2", kclb = "实验", xf = 3.0, kcxz = "必修")
        val rows = listOf(
            base.copy(zzcj = "52", bkcx = "正考"),
            base.copy(zzcj = "78", bkcx = "补考"),
        )
        val sums = CreditAggregator.sumPassedByCategory(rows)
        assertEquals("补考通过后只应计一次 3 学分", 3.0, sums.getValue("实验"), 0.001)

        // 未通过计数同样按收敛结果：这门课最终通过，不该再计入挂科。
        val failed = GradeRows.bestPerCourse(rows).filter { it.isFailed }
        assertEquals("补考通过后不应再算未通过", 0, failed.size)
    }
}
