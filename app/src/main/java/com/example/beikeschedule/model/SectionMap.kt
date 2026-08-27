package com.example.beikeschedule.model

/** 北科节次结构：13 小节 = 6 大节（前五大节各 2 小节，第六大节 3 小节）。 */
object SectionMap {
    /** 每个大节包含的小节区间。 */
    val BIG_SECTIONS: List<IntRange> = listOf(1..2, 3..4, 5..6, 7..8, 9..10, 11..13)

    val BIG_NAMES = listOf("一", "二", "三", "四", "五", "六")

    /** 小节总数（网格定位的基本单位）。 */
    const val TOTAL_SMALL_SECTIONS = 13
}
