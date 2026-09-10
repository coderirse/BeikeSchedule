package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.SessionExpander
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `大节下标全非法时不崩溃`() {
        // bigSections 非空但下标全越界：旧实现 sorted.first() 会抛 NoSuchElementException
        assertEquals(
            emptyList<SessionExpander.Row>(),
            SessionExpander.expand(
                listOf(SessionExpander.Session(dayOfWeek = 1, bigSections = setOf(9, 99))),
            ),
        )
    }

    // ——— 编辑保真：每个时段各自的周次（v1.1.8 修复 A1）———

    private fun course(
        day: Int,
        start: Int,
        end: Int,
        weeks: Set<Int>,
        totalWeeks: Int = 20,
        name: String = "课程",
    ) = CourseEntity(
        taskId = "RWH", name = name, teacher = "教师", location = "机械楼720",
        dayOfWeek = day, startSection = start, endSection = end,
        weekBitmap = SessionExpander.buildWeekBitmap(weeks, totalWeeks),
        colorIndex = 1, source = CourseEntity.SOURCE_IMPORT,
    )

    private fun SessionExpander.EditRow.hasWeek(week: Int): Boolean =
        week in 1 until weekBitmap.length && weekBitmap[week] == '1'

    @Test
    fun `编辑时段保留各自周次_不再被并集抹平`() {
        // 真实场景：同一门课的不同时段落在不同周次（实验课/调课）
        val rows = listOf(
            course(day = 1, start = 3, end = 4, weeks = (1..8).toSet()),
            course(day = 1, start = 7, end = 8, weeks = (9..16).toSet()),
        )
        val edits = SessionExpander.toEditSessions(rows)

        assertEquals(2, edits.size)
        assertEquals((1..8).toSet(), edits[0].weeks)
        assertEquals((9..16).toSet(), edits[1].weeks)

        // 保存后两行仍各带自己的周次（旧实现会把两行都写成 1-16 周）
        val rebuilt = SessionExpander.expandWithWeeks(edits, totalWeeks = 20)
        assertEquals(2, rebuilt.size)
        val first = rebuilt.first { it.startSection == 3 }
        val second = rebuilt.first { it.startSection == 7 }
        assertTrue("3-4 节应在第 8 周有课", first.hasWeek(8))
        assertFalse("3-4 节不应在第 9 周有课", first.hasWeek(9))
        assertFalse("7-8 节不应在第 8 周有课", second.hasWeek(8))
        assertTrue("7-8 节应在第 9 周有课", second.hasWeek(9))
    }

    @Test
    fun `同一时段的多行周次取并集`() {
        // 教务单双周拆行：同一时段两行，合并后每周都点亮
        val rows = listOf(
            course(day = 3, start = 3, end = 4, weeks = setOf(1, 2)),
            course(day = 3, start = 3, end = 4, weeks = setOf(3, 4)),
        )
        val edits = SessionExpander.toEditSessions(rows)
        assertEquals(1, edits.size)
        assertEquals(setOf(1, 2, 3, 4), edits[0].weeks)
    }

    @Test
    fun `无固定时间课程不进入编辑时段`() {
        // dayOfWeek=0 若混进时段会被 expand() 过滤掉，保存时产出 0 行把课程删掉
        val edits = SessionExpander.toEditSessions(
            listOf(course(day = 0, start = 0, end = 0, weeks = (5..7).toSet(), name = "机械设计【实验】")),
        )
        assertEquals(emptyList<SessionExpander.EditSession>(), edits)
    }
}
