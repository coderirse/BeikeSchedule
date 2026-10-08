package com.caeamer.beikeschedule.data.remote

import com.caeamer.beikeschedule.data.local.CourseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 班级实验安排 → 课程行的转换护栏。
 *
 * 服务端 JSON 是**网络来的数据**（可能被改坏/被写脏）：空名、星期越界的场次必须逐条丢弃，
 * 不能把脏行写进课表；周次位图必须与教务 ZC 同构（32 位、index 0 占位、第 N 周对应 index N），
 * 否则网格上周次会整体错位。
 */
class ClassScheduleMapperTest {

    private fun session(
        id: String = "lab-1",
        name: String = "电工实验（第1次）",
        dayOfWeek: Int = 2,
        start: Int = 1,
        end: Int = 4,
        weeks: List<Int> = listOf(9),
        location: String = "201/202 实验室",
        note: String = "8:30 到岗",
        colorIndex: Int = 5,
    ) = ClassSession(id, name, dayOfWeek, start, end, weeks, location, note, colorIndex)

    @Test
    fun `周次位图 32 位且第 N 周对应 index N`() {
        val course = ClassScheduleMapper.toCourses(listOf(session(weeks = listOf(7, 15)))).single()
        assertEquals(32, course.weekBitmap.length)
        assertEquals('0', course.weekBitmap[0]) // index 0 恒为占位，与教务位图同构
        assertEquals('1', course.weekBitmap[7])
        assertEquals('1', course.weekBitmap[15])
        assertEquals('0', course.weekBitmap[8])
    }

    @Test
    fun `来源 稳定键 备注 地点 逐一落位`() {
        val course = ClassScheduleMapper.toCourses(listOf(session())).single()
        assertEquals(CourseEntity.SOURCE_LAB, course.source)
        assertEquals("lab-1", course.taskId)
        assertEquals("电工实验（第1次）", course.name)
        assertEquals("201/202 实验室", course.location)
        assertEquals("8:30 到岗", course.note)
        assertEquals(2, course.dayOfWeek)
        assertEquals(1, course.startSection)
        assertEquals(4, course.endSection)
        assertTrue(course.hasClassOnWeek(9))
        assertEquals("", course.teacher) // 表里没有教师字段
    }

    @Test
    fun `脏数据逐条丢弃：空名与星期越界不写进课表`() {
        val courses = ClassScheduleMapper.toCourses(
            listOf(
                session(name = "  "),
                session(dayOfWeek = 0),
                session(dayOfWeek = 8),
                session(id = "ok", name = "工程数值计算（上机）", dayOfWeek = 3),
            ),
        )
        assertEquals(listOf("工程数值计算（上机）"), courses.map { it.name })
    }

    @Test
    fun `无 id 的场次合成稳定键：同内容跨次同步保持同一 taskId`() {
        val a = ClassScheduleMapper.toCourses(listOf(session(id = "", weeks = listOf(7)))).single()
        val b = ClassScheduleMapper.toCourses(listOf(session(id = "", weeks = listOf(7)))).single()
        assertTrue(a.taskId.isNotBlank())
        assertEquals(a.taskId, b.taskId)
    }

    @Test
    fun `节次倒挂时结束小节回退到起始小节`() {
        val course = ClassScheduleMapper.toCourses(listOf(session(start = 7, end = 2))).single()
        assertEquals(7, course.startSection)
        assertEquals(7, course.endSection)
    }
}
