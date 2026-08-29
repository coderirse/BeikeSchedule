package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.model.SessionExpander
import org.junit.Assert.assertEquals
import org.junit.Test

/** 手动课程编辑的展开逻辑单测。 */
class SessionExpanderTest {

    @Test
    fun `连续大节合并为一行`() {
        val rows = SessionExpander.expand(
            listOf(SessionExpander.Session(dayOfWeek = 1, bigSections = setOf(0, 1))),
        )
        assertEquals(listOf(SessionExpander.Row(1, 1, 4)), rows)
    }

    @Test
    fun `不连续大节拆分为多行`() {
        val rows = SessionExpander.expand(
            listOf(SessionExpander.Session(dayOfWeek = 1, bigSections = setOf(0, 2, 5))),
        )
        assertEquals(
            listOf(
                SessionExpander.Row(1, 1, 2),   // 第一大节 = 1-2 节
                SessionExpander.Row(1, 5, 6),   // 第三大节 = 5-6 节
                SessionExpander.Row(1, 11, 12), // 第六大节 = 11-12 节
            ),
            rows,
        )
    }

    @Test
    fun `多时段按周几分别展开`() {
        val rows = SessionExpander.expand(
            listOf(
                SessionExpander.Session(dayOfWeek = 1, bigSections = setOf(0)),
                SessionExpander.Session(dayOfWeek = 3, bigSections = setOf(0, 1)),
            ),
        )
        assertEquals(
            listOf(
                SessionExpander.Row(1, 1, 2),
                SessionExpander.Row(3, 1, 4),
            ),
            rows,
        )
    }

    @Test
    fun `非法输入被过滤`() {
        val rows = SessionExpander.expand(
            listOf(
                SessionExpander.Session(dayOfWeek = 0, bigSections = setOf(0)),
                SessionExpander.Session(dayOfWeek = 8, bigSections = setOf(0)),
                SessionExpander.Session(dayOfWeek = 1, bigSections = emptySet()),
                SessionExpander.Session(dayOfWeek = 2, bigSections = setOf(0, 9)),
            ),
        )
        assertEquals(listOf(SessionExpander.Row(2, 1, 2)), rows)
    }

    @Test
    fun `存储行还原为编辑时段`() {
        val sessions = SessionExpander.toSessions(
            listOf(
                SessionExpander.Row(1, 1, 2),
                SessionExpander.Row(1, 5, 6),
                SessionExpander.Row(3, 1, 4),
            ),
        )
        assertEquals(
            listOf(
                SessionExpander.Session(1, setOf(0, 2)),
                SessionExpander.Session(3, setOf(0, 1)),
            ),
            sessions,
        )
    }

    @Test
    fun `周次集合构造位图`() {
        val bitmap = SessionExpander.buildWeekBitmap(setOf(1, 3, 18), 18)
        assertEquals(19, bitmap.length)
        assertEquals('0', bitmap[0])
        assertEquals('1', bitmap[1])
        assertEquals('0', bitmap[2])
        assertEquals('1', bitmap[3])
        assertEquals('1', bitmap[18])
        assertEquals(3, bitmap.count { it == '1' })
    }
}
