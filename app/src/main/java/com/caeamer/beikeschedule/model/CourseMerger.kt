package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity

/**
 * 课表渲染前的行合并：教务网对"单周调课/单双周拆分"会把同一门课同一时段拆成多行
 * （如 1-6 周行 + "7周"行 + "8周"行，地点可能写"-"）。同一天、同名、同小节段的多行
 * 合并为一张卡，周次取并集——任一周有课即点亮，观感与修复前一致。
 * 不同课程占用同一时段（真冲突）不在此合并，仍由课表网格并排窄列渲染。
 */
object CourseMerger {

    /** 【校区】前缀（教务地点形如 "【校本部】机械楼720"）。提为常量，避免每次调用重新编译。 */
    private val CAMPUS_PREFIX = Regex("【[^】]*】")

    fun mergeSameSlot(courses: List<CourseEntity>): List<CourseEntity> =
        courses.groupBy { SlotKey(it.name, it.dayOfWeek, it.startSection, it.endSection) }
            .flatMap { (_, rows) -> merge(rows) }

    private data class SlotKey(val name: String, val day: Int, val start: Int, val end: Int)

    /**
     * 同一时段的行合并。**地点不同的真实行不合并**：教务拆行不只因地点写 "-"，也可能是
     * 调课换教室（1-6 周 A 楼、7 周起 B 楼）——若按周次并集合成一张卡，B 楼会被基准行的
     * A 楼覆盖，提醒与详情把用户指去错误教室。占位符行（"-" / "【校区】-"/空）无地点信息，
     * 仍并入其所属地点组；同地点多行合并为一张卡、周次取并集。
     */
    private fun merge(rows: List<CourseEntity>): List<CourseEntity> {
        if (rows.size <= 1) return rows
        val realRows = rows.filter { plausibleLocation(it.location) }
        if (realRows.isEmpty()) return listOf(mergeGroup(rows))
        val baseLocation = stripCampusPrefix(realRows.first().location)
        val baseGroup = rows.filter {
            !plausibleLocation(it.location) || stripCampusPrefix(it.location) == baseLocation
        }
        val otherGroups = realRows
            .filter { stripCampusPrefix(it.location) != baseLocation }
            .groupBy { stripCampusPrefix(it.location) }
        return listOf(mergeGroup(baseGroup)) + otherGroups.values.map { mergeGroup(it) }
    }

    /** 同地点多行 → 一张卡：地点取**首个有真实地点**的行（占位符行可能排在前面），
     *  教师取第一个非空，周次并集。 */
    private fun mergeGroup(rows: List<CourseEntity>): CourseEntity {
        val base = rows.firstOrNull { plausibleLocation(it.location) } ?: rows.first()
        return base.copy(
            teacher = rows.firstOrNull { it.teacher.isNotBlank() }?.teacher ?: base.teacher,
            weekBitmap = orBitmaps(rows.map { it.weekBitmap }),
        )
    }

    /** 去掉【校区】前缀后仍有实际地点内容（非空、非占位符 "-"）。 */
    internal fun plausibleLocation(location: String): Boolean {
        val stripped = stripCampusPrefix(location)
        return stripped.isNotEmpty() && stripped != "-"
    }

    /**
     * 剥掉教务地点里的【校区】前缀（如 "【校本部】机械楼720" → "机械楼720"）。
     *
     * 通知文案与地点"可用性"判定共用同一套剥离规则：若只在通知里剥前缀，
     * "【校本部】-" 会变成裸 "-" 显示给用户。
     */
    fun stripCampusPrefix(location: String): String = location.replace(CAMPUS_PREFIX, "").trim()

    /** 周次位图按位或（长度不齐时取最长）。 */
    internal fun orBitmaps(bitmaps: List<String>): String {
        if (bitmaps.isEmpty()) return ""
        val len = bitmaps.maxOf { it.length }
        return buildString {
            for (i in 0 until len) {
                append(if (bitmaps.any { i < it.length && it[i] == '1' }) '1' else '0')
            }
        }
    }
}
