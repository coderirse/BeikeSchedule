package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.model.CourseMerger
import org.junit.Assert.assertEquals
import org.junit.Test

/** 同名同段多行合并单测（教务单周调课/单双周拆分行）。 */
class CourseMergerTest {

    private fun course(
        name: String,
        day: Int = 3,
        start: Int = 3,
        end: Int = 4,
        zc: String,
        location: String = "机械楼720",
        teacher: String = "韩天",
        source: Int = CourseEntity.SOURCE_IMPORT,
    ) = CourseEntity(
        taskId = "RWH1", name = name, teacher = teacher, location = location,
        dayOfWeek = day, startSection = start, endSection = end,
        weekBitmap = zc, colorIndex = 1, source = source,
    )

    @Test
    fun `同名同段 - 周次并集`() {
        // 真实场景：机电传动控制 1-6周 + 7周调课行 + 8周调课行。
        // 地点用**真实数据形态** "【校本部】-"（不是裸 "-"）：
        // 这一行的原实现判据是 `location != "-"`，而 "【校本部】-" 既非空白也不等于裸 "-"，
        // 于是判据完全失效、卡片上显示了幽灵地点。旧测试用裸 "-" 作 fixture，
        // 所以这个缺陷一直没被测出来——fixture 形态必须与真实数据一致。
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("机电传动控制", zc = "0111111000000000000000000000000000"),
                course("机电传动控制", zc = "0000000100000000000000000000000000", location = "【校本部】-"),
                course("机电传动控制", zc = "0000000010000000000000000000000000", location = "【校本部】-"),
            ),
        )
        assertEquals(1, merged.size)
        // 1-8 周都亮
        assertEquals("0111111110000000000000000000000000", merged[0].weekBitmap)
        // 基准行保留完整地点，不取调课行的 "【校本部】-"
        assertEquals("机械楼720", merged[0].location)
    }

    @Test
    fun `调课行仅占位地点时 - 优先采用有真实地点的行`() {
        // 首行是占位地点 "【校本部】-"，第二行才有真实地点：
        // 判据必须能识别占位符，否则会把幽灵地点显示到卡片上。
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("某课", zc = "0111111000000000000000000000000000", location = "【校本部】-"),
                course("某课", zc = "0000000100000000000000000000000000", location = "【校本部】实验楼301"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("【校本部】实验楼301", merged[0].location)
    }

    @Test
    fun `地点可用性判定 - 剥掉校区前缀后再比较`() {
        assertEquals(true, CourseMerger.plausibleLocation("【校本部】机械楼720"))
        assertEquals(true, CourseMerger.plausibleLocation("机械楼720"))
        assertEquals(false, CourseMerger.plausibleLocation("【校本部】-"))
        assertEquals(false, CourseMerger.plausibleLocation("-"))
        assertEquals(false, CourseMerger.plausibleLocation("【校本部】"))
        assertEquals(false, CourseMerger.plausibleLocation(""))
        assertEquals(false, CourseMerger.plausibleLocation("【校本部】   "))
    }

    @Test
    fun `剥校区前缀 - 通知与地点判定共用同一规则`() {
        assertEquals("机械楼720", CourseMerger.stripCampusPrefix("【校本部】机械楼720"))
        assertEquals("-", CourseMerger.stripCampusPrefix("【校本部】-"))
        assertEquals("机械楼720", CourseMerger.stripCampusPrefix("机械楼720"))
    }

    @Test
    fun `单双周拆分 - 合并后每周都亮`() {
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("高数", zc = "0101010100000000000000000000000000"),
                course("高数", zc = "0010101010000000000000000000000000"),
            ),
        )
        assertEquals(1, merged.size)
        (1..8).forEach { w -> assertEquals(true, merged[0].hasClassOnWeek(w)) }
        assertEquals(false, merged[0].hasClassOnWeek(9))
    }

    @Test
    fun `不同课程同时段 - 不合并`() {
        val merged = CourseMerger.mergeSameSlot(
            listOf(course("机电传动控制", zc = "0111111000000000000000000000000000"), course("工程材料", zc = "0000000110000000000000000000000000")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `同名不同段 - 不合并`() {
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("电子技术实习C", start = 9, end = 12, zc = "0111111000000000000000000000000000"),
                course("电子技术实习C", start = 1, end = 2, zc = "0000000010000000000000000000000000"),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `单行 - 原样返回`() {
        val merged = CourseMerger.mergeSameSlot(listOf(course("机械设计", zc = "0111111000000000000000000000000000")))
        assertEquals(1, merged.size)
        assertEquals("机械楼720", merged[0].location)
    }

    /**
     * 回归：合并键必须含来源。隐藏/删除/编辑都按「课程名 + 来源」取行组
     * （ScheduleScreen.groupOf），跨来源合并成一张卡后另一来源的行不在组内——
     * 隐藏了卡片还留在网格上、编辑只改到一半。触发路径：手动添加一门与教务导入课
     * 同名同时段的课（教务漏排时用户会这么做）。
     */
    @Test
    fun `同名同段但来源不同 - 不合并`() {
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("机械设计", zc = "0111111000000000000000000000000000"),
                course(
                    "机械设计", zc = "0111111000000000000000000000000000",
                    source = CourseEntity.SOURCE_MANUAL, teacher = "",
                ),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(
            setOf(CourseEntity.SOURCE_IMPORT, CourseEntity.SOURCE_MANUAL),
            merged.map { it.source }.toSet(),
        )
    }

    @Test
    fun `同来源的拆分行仍照常合并`() {
        val merged = CourseMerger.mergeSameSlot(
            listOf(
                course("机电传动控制", zc = "0111111000000000000000000000000000"),
                course("机电传动控制", zc = "0000000100000000000000000000000000"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("0111111100000000000000000000000000", merged[0].weekBitmap)
    }
}
