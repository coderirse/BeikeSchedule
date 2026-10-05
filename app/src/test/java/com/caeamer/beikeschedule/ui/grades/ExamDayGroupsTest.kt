package com.caeamer.beikeschedule.ui.grades

import com.caeamer.beikeschedule.data.local.ExamEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 考试按日期分组的回归护栏。
 *
 * 重点是「时间待定」与「日期解析失败」这两组不能互相覆盖：它们都排在最后，只按日期比较时
 * TreeMap 把两个不同的 key 当成同一个，后放进去的整组考试会静默消失且没有任何提示。
 */
class ExamDayGroupsTest {

    private fun exam(kcmc: String, ksrq: String, kssjms: String = "") = ExamEntity(
        kcdm = "", kcmc = kcmc, kslx = "期末考试", kssjms = kssjms, ksrq = ksrq,
        kssj = "", jssj = "", cdmc = "", zwh = "", jkjsbz = "", kkyxmc = "", xnxq = "2026-20271",
    )

    @Test
    fun `待定与解析失败两组都在`() {
        val grouped = examDayGroups(
            listOf(
                exam("大学物理B", "", "第16周 星期三"),
                exam("机械原理", "2027/1/15", "脏日期串"),
            ),
        )
        assertEquals(2, grouped.size)
        assertEquals(listOf("大学物理B"), grouped[EXAM_PENDING_DAY].orEmpty().map { it.kcmc })
        assertEquals(listOf("机械原理"), grouped["2027/1/15"].orEmpty().map { it.kcmc })
    }

    @Test
    fun `按日期升序_同日合并_待定沉底`() {
        val grouped = examDayGroups(
            listOf(
                exam("A", ""),
                exam("B", "2027-01-20"),
                exam("C", "2027-01-15"),
                exam("D", "2027-01-15"),
            ),
        )
        assertEquals(listOf("2027-01-15", "2027-01-20", EXAM_PENDING_DAY), grouped.keys.toList())
        assertEquals(listOf("C", "D"), grouped["2027-01-15"]?.map { it.kcmc })
    }

    @Test
    fun `空输入得到空分组`() {
        assertEquals(emptyMap<String, List<ExamEntity>>(), examDayGroups(emptyList()))
    }
}
