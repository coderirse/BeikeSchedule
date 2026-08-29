package com.caeamer.beikeschedule.model

/** 北科节次结构：官方 6 大节，每大节 2 小节（第六大节 11-12 节，19:30-21:05）。 */
object SectionMap {
    /** 每个大节包含的小节区间。 */
    val BIG_SECTIONS: List<IntRange> = listOf(1..2, 3..4, 5..6, 7..8, 9..10, 11..12)

    val BIG_NAMES = listOf("一", "二", "三", "四", "五", "六")

    /**
     * 网格定位的小节单位数。
     * 教务数据里存在 13 节（21:10-21:55）的极少量特殊加课，网格按 12 单位排版，
     * 13 节课程在显示时钳制到第 12 节区间（详情仍显示真实节次）。
     */
    const val TOTAL_SMALL_SECTIONS = 12

    /** 小节所属大节下标（0 起）；13 节归入第六大节。 */
    fun bigIndexOf(section: Int): Int {
        val i = BIG_SECTIONS.indexOfFirst { section in it }
        return if (i >= 0) i else BIG_SECTIONS.lastIndex
    }

    /** 大节区间的人类可读描述："第二大节"、"第三~四大节"。 */
    fun describeBigSections(startSection: Int, endSection: Int): String {
        val a = bigIndexOf(startSection)
        val b = bigIndexOf(endSection)
        return if (a == b) "第${BIG_NAMES[a]}大节" else "第${BIG_NAMES[a]}~${BIG_NAMES[b]}大节"
    }
}
