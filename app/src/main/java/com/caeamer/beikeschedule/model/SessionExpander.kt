package com.caeamer.beikeschedule.model

/**
 * 手动编辑课程的会话模型与展开逻辑：UI 以"周几 + 大节集合"编辑，
 * 存储以连续小节区间（startSection/endSection）逐行展开，与教务导入数据同构。
 */
object SessionExpander {

    /** 一个时段：星期几（1..7）+ 选中的大节下标集合（0..5，对应 SectionMap.BIG_SECTIONS）。 */
    data class Session(val dayOfWeek: Int, val bigSections: Set<Int>)

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
