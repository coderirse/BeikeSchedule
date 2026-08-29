package com.caeamer.beikeschedule.model

/** 周次位图工具：与 CourseEntity.weekBitmap 语义一致（bitmap[i] 对应第 i 周，0 号位占位）。 */
object WeekUtils {

    const val WEEK_TYPE_ALL = 0
    const val WEEK_TYPE_ODD = 1
    const val WEEK_TYPE_EVEN = 2

    /** 手动添加课程时，由 起始周/结束周/单双周 构造位图（长度 totalWeeks+1）。 */
    fun buildWeekBitmap(startWeek: Int, endWeek: Int, weekType: Int, totalWeeks: Int): String {
        val sb = StringBuilder("0") // 0 号位占位
        for (w in 1..totalWeeks) {
            val inRange = w in startWeek..endWeek
            val parityOk = when (weekType) {
                WEEK_TYPE_ODD -> w % 2 == 1
                WEEK_TYPE_EVEN -> w % 2 == 0
                else -> true
            }
            sb.append(if (inRange && parityOk) '1' else '0')
        }
        return sb.toString()
    }

    /** 位图中所有有课的周次列表。 */
    fun weeksOf(bitmap: String): List<Int> =
        (1 until bitmap.length).filter { bitmap[it] == '1' }

    /** 单/双周标签：全部奇数周→"单"，全部偶数周→"双"，否则空串。 */
    fun oddEvenLabel(bitmap: String): String {
        val weeks = weeksOf(bitmap)
        if (weeks.isEmpty()) return ""
        return when {
            weeks.all { it % 2 == 1 } -> "单"
            weeks.all { it % 2 == 0 } -> "双"
            else -> ""
        }
    }

    /** 人类可读周数描述，如 "1-8周"、"1-16周 单周"、"1-3,5-8周"。 */
    fun describe(bitmap: String): String {
        val weeks = weeksOf(bitmap)
        if (weeks.isEmpty()) return "无周次"
        val ranges = mutableListOf<String>()
        var start = weeks.first()
        var prev = start
        for (w in weeks.drop(1)) {
            if (w == prev + 1) {
                prev = w
            } else {
                ranges += if (start == prev) "$start" else "$start-$prev"
                start = w; prev = w
            }
        }
        ranges += if (start == prev) "$start" else "$start-$prev"
        val label = oddEvenLabel(bitmap)
        return ranges.joinToString(",") + "周" + if (label.isNotEmpty()) " ${label}周" else ""
    }
}
