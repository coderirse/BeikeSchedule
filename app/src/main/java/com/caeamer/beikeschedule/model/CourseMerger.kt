package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity

/**
 * 课表渲染前的行合并：教务网对"单周调课/单双周拆分"会把同一门课同一时段拆成多行
 * （如 1-6 周行 + "7周"行 + "8周"行，地点可能写"-"）。同一天、同名、同小节段的多行
 * 合并为一张卡，周次取并集——任一周有课即点亮，观感与修复前一致。
 * 不同课程占用同一时段（真冲突）不在此合并，仍由课表网格并排窄列渲染。
 */
object CourseMerger {

    fun mergeSameSlot(courses: List<CourseEntity>): List<CourseEntity> =
        courses.groupBy { SlotKey(it.name, it.dayOfWeek, it.startSection, it.endSection) }
            .map { (_, rows) -> merge(rows) }

    private data class SlotKey(val name: String, val day: Int, val start: Int, val end: Int)

    private fun merge(rows: List<CourseEntity>): CourseEntity {
        if (rows.size == 1) return rows[0]
        // 基准行取地点信息最完整的（调课行地点常为"-"）
        val base = rows.firstOrNull { it.location.isNotBlank() && it.location != "-" } ?: rows.first()
        return base.copy(
            teacher = rows.firstOrNull { it.teacher.isNotBlank() }?.teacher ?: base.teacher,
            weekBitmap = orBitmaps(rows.map { it.weekBitmap }),
        )
    }

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
