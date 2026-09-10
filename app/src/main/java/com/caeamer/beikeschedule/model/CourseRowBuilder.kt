package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity

/**
 * 课程编辑结果 → 存储行（纯函数，便于单测）。
 *
 * 这里是"编辑一下就把课表改坏"这类缺陷的集中点，所以从对话框里抽出来独立测试：
 * 1. 每个时段写**自己的**周次位图，绝不把所有时段共用一个并集位图；
 * 2. 无固定时间行（dayOfWeek=0）原样透传 —— 它们不在时段编辑器里，
 *    若按"只保存 sessions"处理会被整体丢弃（旧版"编辑无固定时间课程 = 删除"的根因）；
 * 3. 未改动的时段沿用原行的 taskId/source/hidden，只有新增时段才新建行。
 */
object CourseRowBuilder {

    /** 编辑框收集到的可编辑字段。 */
    data class Edit(
        val name: String,
        val teacher: String,
        val location: String,
        val colorIndex: Int,
        /** 时段编辑器里的时段，每个时段带自己的周次集合。 */
        val sessions: List<SessionExpander.EditSession>,
        /** 整门课都无固定时间时使用的共用周次集合。 */
        val unscheduledWeeks: Set<Int>,
        val totalWeeks: Int,
    )

    /**
     * @param initialRows 编辑前的全部行（同「课程名 + 来源」）；新增课程时为空表。
     * @return 保存用的行列表（id 一律为 0，由 Room 重新分配；旧行由调用方按 id 删除）。
     */
    fun build(initialRows: List<CourseEntity>, edit: Edit): List<CourseEntity> {
        val scheduled = initialRows.filter { !it.isUnscheduled }
        val unscheduled = initialRows.filter { it.isUnscheduled }
        // 注意：新增课程时两者都为空，此时必须走 sessions 分支，否则会保存出 0 行
        val onlyUnscheduled = scheduled.isEmpty() && unscheduled.isNotEmpty()
        val defaultSource = initialRows.firstOrNull()?.source ?: CourseEntity.SOURCE_MANUAL

        // 无固定时间的行：只更新共用的描述字段与颜色；整门课都无固定时间时周次也一起改，
        // 混合场景下这些行不在编辑器里，保留它们各自的周次不动。
        val passthrough = unscheduled.map { row ->
            row.copy(
                id = 0,
                name = edit.name,
                teacher = edit.teacher,
                location = edit.location,
                weekBitmap = if (onlyUnscheduled) {
                    SessionExpander.buildWeekBitmap(edit.unscheduledWeeks, edit.totalWeeks)
                } else {
                    row.weekBitmap
                },
                colorIndex = edit.colorIndex,
            )
        }
        if (onlyUnscheduled) return passthrough

        val templates = scheduled.associateBy { Triple(it.dayOfWeek, it.startSection, it.endSection) }
        val scheduledRows = SessionExpander.expandWithWeeks(edit.sessions, edit.totalWeeks).map { row ->
            val template = templates[Triple(row.dayOfWeek, row.startSection, row.endSection)]
            template?.copy(
                id = 0,
                name = edit.name,
                teacher = edit.teacher,
                location = edit.location,
                weekBitmap = row.weekBitmap,
                colorIndex = edit.colorIndex,
            ) ?: CourseEntity(
                id = 0,
                taskId = "",
                name = edit.name,
                teacher = edit.teacher,
                location = edit.location,
                dayOfWeek = row.dayOfWeek,
                startSection = row.startSection,
                endSection = row.endSection,
                weekBitmap = row.weekBitmap,
                colorIndex = edit.colorIndex,
                source = defaultSource,
            )
        }
        return scheduledRows + passthrough
    }
}
