package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseRowBuilder
import com.caeamer.beikeschedule.model.SessionExpander
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程编辑 → 保存行 的单测（v1.1.8 修复 A1 的核心）。
 * 覆盖三类曾经会静默损坏数据的路径：周次被并集抹平、无固定时间课程被删除、
 * 新增课程的边界（initialRows 为空）。
 */
class CourseRowBuilderTest {

    private fun course(
        day: Int,
        start: Int,
        end: Int,
        weeks: Set<Int>,
        name: String = "机械设计",
        totalWeeks: Int = 20,
        source: Int = CourseEntity.SOURCE_IMPORT,
        taskId: String = "RWH",
    ) = CourseEntity(
        taskId = taskId, name = name, teacher = "张杰", location = "机械楼720",
        dayOfWeek = day, startSection = start, endSection = end,
        weekBitmap = SessionExpander.buildWeekBitmap(weeks, totalWeeks),
        colorIndex = 1, source = source,
    )

    private fun edit(
        sessions: List<SessionExpander.EditSession>,
        unscheduledWeeks: Set<Int> = emptySet(),
        colorIndex: Int = 3,
        name: String = "机械设计",
        totalWeeks: Int = 20,
    ) = CourseRowBuilder.Edit(
        name = name, teacher = "李四", location = "实验楼101", colorIndex = colorIndex,
        sessions = sessions, unscheduledWeeks = unscheduledWeeks, totalWeeks = totalWeeks,
    )

    private fun CourseEntity.weeks(): List<Int> =
        (1 until weekBitmap.length).filter { weekBitmap[it] == '1' }

    @Test
    fun `各时段保留自己的周次_不共用并集位图`() {
        val rows = listOf(
            course(day = 1, start = 3, end = 4, weeks = (1..8).toSet()),
            course(day = 1, start = 7, end = 8, weeks = (9..16).toSet()),
        )
        // 模拟用户什么都没改直接保存：时段沿用各行的周次
        val sessions = SessionExpander.toEditSessions(rows).map {
            SessionExpander.EditSession(it.dayOfWeek, it.bigSections, it.weeks)
        }
        val saved = CourseRowBuilder.build(rows, edit(sessions))

        assertEquals(2, saved.size)
        val a = saved.first { it.startSection == 3 }
        val b = saved.first { it.startSection == 7 }
        assertEquals((1..8).toList(), a.weeks())
        assertEquals((9..16).toList(), b.weeks())
        // 旧实现两行都会是 1-16 周
        assertFalse(a.weeks().contains(9))
        assertFalse(b.weeks().contains(8))
    }

    @Test
    fun `未改动的时段沿用原行的 taskId 与 source`() {
        val rows = listOf(course(day = 2, start = 1, end = 2, weeks = (1..8).toSet()))
        val saved = CourseRowBuilder.build(
            rows,
            edit(listOf(SessionExpander.EditSession(2, setOf(0), (1..8).toSet()))),
        )
        assertEquals(1, saved.size)
        assertEquals("RWH", saved[0].taskId)
        assertEquals(CourseEntity.SOURCE_IMPORT, saved[0].source)
        assertEquals(0L, saved[0].id)
        assertEquals("李四", saved[0].teacher)
        assertEquals(3, saved[0].colorIndex)
    }

    @Test
    fun `新增时段才新建手动行`() {
        val rows = listOf(course(day = 2, start = 1, end = 2, weeks = (1..8).toSet()))
        val saved = CourseRowBuilder.build(
            rows,
            edit(
                listOf(
                    SessionExpander.EditSession(2, setOf(0), (1..8).toSet()),
                    SessionExpander.EditSession(4, setOf(2), (1..8).toSet()), // 新增时段
                ),
            ),
        )
        assertEquals(2, saved.size)
        val added = saved.first { it.dayOfWeek == 4 }
        assertEquals("", added.taskId)
        // 新增行沿用原课程的 source（保持与旧行为一致）
        assertEquals(CourseEntity.SOURCE_IMPORT, added.source)
    }

    @Test
    fun `编辑无固定时间课程不会被删除`() {
        // 旧实现在这里产出 0 行，配合 replaceIds 直接把课程删掉
        val rows = listOf(
            course(day = 0, start = 0, end = 0, weeks = (5..7).toSet(), name = "机械设计【实验】"),
        )
        val saved = CourseRowBuilder.build(
            rows,
            edit(sessions = emptyList(), unscheduledWeeks = (5..7).toSet(), name = "机械设计【实验】"),
        )

        assertEquals(1, saved.size)
        assertEquals(0, saved[0].dayOfWeek)
        assertTrue(saved[0].isUnscheduled)
        assertEquals((5..7).toList(), saved[0].weeks())
        assertEquals("RWH", saved[0].taskId) // dayOfWeek/小节/taskId 原样保留
        assertEquals("实验楼101", saved[0].location)
    }

    @Test
    fun `混合课程的无固定时间行被原样保留`() {
        // 同名课程既有周几+大节行，也有教务备注行：备注行不在时段编辑器里，绝不能被丢掉
        val rows = listOf(
            course(day = 1, start = 1, end = 2, weeks = (1..16).toSet()),
            course(day = 0, start = 0, end = 0, weeks = (17..18).toSet()),
        )
        val saved = CourseRowBuilder.build(
            rows,
            edit(listOf(SessionExpander.EditSession(1, setOf(0), (1..16).toSet())), colorIndex = 7),
        )

        assertEquals(2, saved.size)
        val note = saved.first { it.isUnscheduled }
        assertEquals((17..18).toList(), note.weeks()) // 自己的周次不被时段编辑影响
        assertEquals(7, note.colorIndex)
        assertTrue(saved.any { !it.isUnscheduled })
    }

    @Test
    fun `新增课程时不会保存出空列表`() {
        // 新增课程 initialRows 为空 → 若误判为"仅无固定时间"会返回 0 行，课程根本存不进去
        val saved = CourseRowBuilder.build(
            emptyList(),
            edit(listOf(SessionExpander.EditSession(3, setOf(0, 1), (1..16).toSet())), name = "新课程"),
        )
        assertEquals(1, saved.size)
        assertEquals(3, saved[0].dayOfWeek)
        assertEquals(1, saved[0].startSection)
        assertEquals(4, saved[0].endSection)
        assertEquals("新课程", saved[0].name)
        assertEquals(CourseEntity.SOURCE_MANUAL, saved[0].source)
    }
}
