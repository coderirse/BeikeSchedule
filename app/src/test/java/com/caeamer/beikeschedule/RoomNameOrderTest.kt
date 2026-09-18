package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.model.RoomNameOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 教室名自然排序单测。 */
class RoomNameOrderTest {

    private fun sort(vararg names: String): List<String> = names.sortedWith(RoomNameOrder)

    @Test
    fun `同楼同位数按数值升序`() {
        assertEquals(
            listOf("教学楼503", "教学楼507", "教学楼512"),
            sort("教学楼512", "教学楼503", "教学楼507"),
        )
    }

    @Test
    fun `位数不同时按数值而非字符序`() {
        // 逐字符比较会把 "1002" 排在 "503" 前面（'1' < '5'），这是本类的存在理由
        assertEquals(
            listOf("教学楼503", "教学楼1002"),
            sort("教学楼1002", "教学楼503"),
        )
    }

    @Test
    fun `前导零不影响比较`() {
        // 前导零必须在数字段内部被归一化："0503" 的数值是 503，因此排在 1002 之前。
        // 若不做归一化，"0503" 会被当成独立的数字段而与 "1002" 无法按数值比较
        // （这正是本类最初版本的缺陷，由本用例守住）。
        assertEquals(listOf("教学楼0503", "教学楼1002"), sort("教学楼1002", "教学楼0503"))
        assertEquals(listOf("A楼007", "A楼70"), sort("A楼70", "A楼007"))
    }

    @Test
    fun `不同楼栋按首字的 Unicode 码点排序`() {
        // 中文没有拼音序，CompareTo 按码点：教(U+6559) < 文(U+6587) < 逸(U+9038) < 高(U+9AD8)。
        // 这是刻意的取舍：不引入拼音库，楼栋名在界面上本来就是固定几栋、顺序稳定即可。
        val sorted = sort("逸夫楼107", "教学楼503", "高工302", "文法楼403")
        assertEquals(listOf("教学楼503", "文法楼403", "逸夫楼107", "高工302"), sorted)
    }

    @Test
    fun `多段数字时逐段比较`() {
        // "主楼3层05" 这种带多个数字段的名字
        assertEquals(listOf("主楼3-1", "主楼3-2", "主楼10-1"), sort("主楼10-1", "主楼3-2", "主楼3-1"))
    }

    @Test
    fun `无数字名字之间按文本排序`() {
        assertEquals(listOf("A楼", "B楼"), sort("B楼", "A楼"))
    }

    @Test
    fun `纯数字名可与其他名混排`() {
        // 数字段优先于同位置的文本段
        assertEquals(listOf("503", "A503"), sort("A503", "503"))
    }

    @Test
    fun `排序稳定 - 完全相同时不抛异常`() {
        val sorted = sort("教学楼503", "教学楼503")
        assertEquals(listOf("教学楼503", "教学楼503"), sorted)
    }

    @Test
    fun `前缀相同时短的在前`() {
        assertEquals(listOf("教学楼", "教学楼503"), sort("教学楼503", "教学楼"))
    }

    @Test
    fun `空串参与排序不崩`() {
        val sorted = sort("", "教学楼503")
        assertEquals("", sorted.first())
        assertEquals("教学楼503", sorted.last())
    }

    @Test
    fun `超长数字段退化为文本比较且不崩`() {
        // 位数超过 Long 时 toLongOrNull 返回 null，走文本兜底
        val huge = "9".repeat(25)
        val sorted = sort("楼$huge", "楼503")
        assertEquals(2, sorted.size)
        assertTrue(sorted.contains("楼503"))
    }

    @Test
    fun `大小写不影响文本段比较`() {
        assertEquals(listOf("a楼503", "B楼503"), sort("B楼503", "a楼503"))
    }
}
