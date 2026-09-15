package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity

/**
 * 手动编辑课程的会话模型与展开逻辑：UI 以"周几 + 大节集合 + 该时段自己的周次"编辑，
 * 存储以连续小节区间（startSection/endSection）逐行展开，与教务导入数据同构。
 */
object SessionExpander {

    /** 一个时段：星期几（1..7）+ 选中的大节下标集合（0..5，对应 SectionMap.BIG_SECTIONS）。 */
    data class Session(val dayOfWeek: Int, val bigSections: Set<Int>)

    /**
     * 编辑框用的时段：周几 + 大节集合 + **该时段自己的**周次集合。
     *
     * 绝不能把一门课所有行的周次并成一个集合再写回每一行：教务的单双周/调课拆行、
     * 以及不同时段不同周次的实验课（如 3-4 节 1-8 周 + 7-8 节 9-16 周），
     * 一旦抹平，课表会变成"每周每段都有课"。
     */
    data class EditSession(val dayOfWeek: Int, val bigSections: Set<Int>, val weeks: Set<Int>)

    /** 展开后的一行：连续小节区间 + 该行自己的周次位图。 */
    data class EditRow(
        val dayOfWeek: Int,
        val startSection: Int,
        val endSection: Int,
        val weekBitmap: String,
    )

    /** 展开后的一行：连续小节区间。 */
    data class Row(val dayOfWeek: Int, val startSection: Int, val endSection: Int)

    /**
     * 时段列表 → 存储行列表。
     * 同一时段内连续大节合并为一行（一+二大节 = 第 1-4 节），
     * 不连续大节拆分为多行（一+三大节 = 第 1-2 节、第 5-6 节两行）。
     */
    fun expand(sessions: List<Session>): List<Row> =
        sessions.filter { it.dayOfWeek in 1..7 && it.bigSections.isNotEmpty() }
            .flatMap { session ->
                val sorted = session.bigSections.filter { it in SectionMap.BIG_SECTIONS.indices }.sorted()
                // bigSections 非空但下标全非法时 sorted 会是空表，直接 first() 会抛 NoSuchElementException
                if (sorted.isEmpty()) return@flatMap emptyList()
                val rows = mutableListOf<Row>()
                var runStart = sorted.first()
                var prev = sorted.first()
                sorted.drop(1).forEach { b ->
                    if (b == prev + 1) {
                        prev = b
                    } else {
                        rows += session.toRow(runStart, prev)
                        runStart = b; prev = b
                    }
                }
                rows += session.toRow(runStart, prev)
                rows
            }

    /** 存储行 → 编辑用时段（同一 周几 的若干连续区间合并为大节集合）。 */
    fun toSessions(rows: List<Row>): List<Session> =
        rows.groupBy { it.dayOfWeek }.map { (day, dayRows) ->
            Session(
                dayOfWeek = day,
                bigSections = dayRows.flatMap { row ->
                    (SectionMap.bigIndexOf(row.startSection)..SectionMap.bigIndexOf(row.endSection)).toList()
                }.toSet(),
            )
        }.sortedBy { it.dayOfWeek }

    /**
     * 存储行 → 编辑用时段（**保留每个时段各自的周次**）。
     *
     * 按 (周几, 起小节, 止小节) 分组：同一时段的多行（教务单双周/调课拆行）取周次并集
     * —— 与 CourseMerger 的展示口径一致；不同时段各自带着自己的周次，互不污染。
     * 无固定时间课程（dayOfWeek=0）被排除：它们没有可编辑的时段，
     * 若混进来会被 expand() 过滤掉，导致"保存"产出 0 行把课程删掉。
     */
    fun toEditSessions(rows: List<CourseEntity>): List<EditSession> =
        rows.filter { !it.isUnscheduled }
            .groupBy { Triple(it.dayOfWeek, it.startSection, it.endSection) }
            .map { (key, group) ->
                EditSession(
                    dayOfWeek = key.first,
                    bigSections = (SectionMap.bigIndexOf(key.second)..SectionMap.bigIndexOf(key.third)).toSet(),
                    weeks = group.flatMap { WeekUtils.weeksOf(it.weekBitmap) }.toSet(),
                )
            }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.bigSections.minOrNull() ?: 0 }))

    /** 编辑用时段 → 存储行：每个时段写入**自己的**周次位图，不再共用一个并集位图。 */
    fun expandWithWeeks(sessions: List<EditSession>, totalWeeks: Int): List<EditRow> =
        sessions.flatMap { session ->
            val bitmap = buildWeekBitmap(session.weeks, totalWeeks)
            expand(listOf(Session(session.dayOfWeek, session.bigSections))).map {
                EditRow(it.dayOfWeek, it.startSection, it.endSection, bitmap)
            }
        }

    /** 周次集合 → 位图（bitmap[i] 对应第 i 周，0 号位占位，长度 totalWeeks+1）。 */
    fun buildWeekBitmap(weeks: Set<Int>, totalWeeks: Int): String {
        val sb = StringBuilder("0")
        for (w in 1..totalWeeks) sb.append(if (w in weeks) '1' else '0')
        return sb.toString()
    }

    private fun Session.toRow(firstBig: Int, lastBig: Int): Row =
        Row(
            dayOfWeek = dayOfWeek,
            startSection = SectionMap.BIG_SECTIONS[firstBig].first,
            endSection = SectionMap.BIG_SECTIONS[lastBig].last,
        )
}
