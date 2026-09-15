package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.WeekLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表一天列布局的单测（v1.1.11 引入「隐藏本周不上的课」时从 ScheduleScreen 抽出）。
 * 覆盖：冲突分簇不变量、"本周不上"的淡化课筛选、以及开关开启后不再返回它们。
 */
class WeekLayoutTest {

    private fun course(
        id: Long,
        day: Int = 1,
        start: Int,
        end: Int,
        weeks: Set<Int> = setOf(1, 2, 3, 4),
        name: String = "课程$id",
    ) = CourseEntity(
        id = id, taskId = "", name = name, teacher = "", location = "",
        dayOfWeek = day, startSection = start, endSection = end,
        weekBitmap = "0" + (1..20).joinToString("") { if (it in weeks) "1" else "0" },
        colorIndex = 0, source = CourseEntity.SOURCE_IMPORT,
    )

    @Test
    fun `本周有课且重叠的课程分到同一簇`() {
        val layout = WeekLayout.layoutDay(
            courses = listOf(
                course(1, start = 1, end = 2),
                course(2, start = 2, end = 3),
                course(3, start = 3, end = 4),
            ),
            day = 1, week = 1, hideInactive = false,
        )
        assertEquals(1, layout.clusters.size)
        assertEquals(listOf(1L, 2L, 3L), layout.clusters[0].map { it.id })
    }

    @Test
    fun `本周有课但不重叠的课程各自成簇`() {
        val layout = WeekLayout.layoutDay(
            courses = listOf(
                course(1, start = 1, end = 2),
                course(2, start = 5, end = 6),
            ),
            day = 1, week = 1, hideInactive = false,
        )
        assertEquals(2, layout.clusters.size)
        assertTrue(layout.inactives.isEmpty())
    }

    @Test
    fun `只处理指定星期几`() {
        val layout = WeekLayout.layoutDay(
            courses = listOf(course(1, day = 1, start = 1, end = 2), course(2, day = 3, start = 1, end = 2)),
            day = 1, week = 1, hideInactive = false,
        )
        assertEquals(listOf(1L), layout.clusters.flatten().map { it.id })
        assertTrue(layout.inactives.isEmpty())
    }

    @Test
    fun `本周没课的课程进入淡化列表`() {
        // 单双周：第 1 周只有 id=1，id=2 在第 2 周
        val layout = WeekLayout.layoutDay(
            courses = listOf(
                course(1, start = 1, end = 2, weeks = setOf(1, 3)),
                course(2, start = 5, end = 6, weeks = setOf(2, 4)),
            ),
            day = 1, week = 1, hideInactive = false,
        )
        assertEquals(listOf(1L), layout.clusters.flatten().map { it.id })
        assertEquals(listOf(2L), layout.inactives.map { it.id })
    }

    @Test
    fun `与本周课程重叠的淡化课不显示`() {
        // 淡化课只能占"不与任何本周课程重叠"的空位
        val layout = WeekLayout.layoutDay(
            courses = listOf(
                course(1, start = 1, end = 4, weeks = setOf(1)),
                course(2, start = 3, end = 6, weeks = setOf(2)), // 与 id=1 在 3-4 节重叠
            ),
            day = 1, week = 1, hideInactive = false,
        )
        assertEquals(listOf(1L), layout.clusters.flatten().map { it.id })
        assertTrue("重叠的非本周课程应被跳过", layout.inactives.isEmpty())
    }

    // ——— 新功能：隐藏本周不上的课 ———

    @Test
    fun `开关开启后不再返回本周没课的课程`() {
        val courses = listOf(
            course(1, start = 1, end = 2, weeks = setOf(1, 3)),
            course(2, start = 5, end = 6, weeks = setOf(2, 4)),
        )
        val off = WeekLayout.layoutDay(courses, day = 1, week = 1, hideInactive = false)
        val on = WeekLayout.layoutDay(courses, day = 1, week = 1, hideInactive = true)

        assertEquals(listOf(2L), off.inactives.map { it.id })
        assertTrue("开启后淡化列表必须为空", on.inactives.isEmpty())
        // 本周要上的课不受影响
        assertEquals(off.clusters.flatten().map { it.id }, on.clusters.flatten().map { it.id })
    }

    @Test
    fun `开关开启后换到另一周_该周要上的课照常显示`() {
        val courses = listOf(
            course(1, start = 1, end = 2, weeks = setOf(1, 3)),
            course(2, start = 5, end = 6, weeks = setOf(2, 4)),
        )
        // 第 2 周：轮到 id=2 上课，id=1 变成"本周不上"→ 被隐藏
        val week2 = WeekLayout.layoutDay(courses, day = 1, week = 2, hideInactive = true)
        assertEquals(listOf(2L), week2.clusters.flatten().map { it.id })
        assertTrue(week2.inactives.isEmpty())
    }

    @Test
    fun `开关关闭时行为与旧实现一致`() {
        // 旧实现的语义：clusters = 本周重叠簇；inactives = 不重叠的其余本周没课课程
        val layout = WeekLayout.layoutDay(
            courses = listOf(
                course(1, start = 1, end = 2, weeks = setOf(1)),
                course(2, start = 1, end = 2, weeks = setOf(1)),
                course(3, start = 5, end = 6, weeks = setOf(2)),
                course(4, start = 7, end = 8, weeks = setOf(2)),
            ),
            day = 1, week = 1, hideInactive = false,
        )
        assertEquals(listOf(1L, 2L), layout.clusters.single().map { it.id })
        assertEquals(listOf(3L, 4L), layout.inactives.map { it.id })
    }
}
