package com.caeamer.beikeschedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 个人日程事项（用户手动创建，本地持久化，与教务/课程数据完全独立）。
 *
 * 重复规则三种：
 * - [REPEAT_DAILY]：每天在该时间点出现；
 * - [REPEAT_WEEKLY]：每周固定几天出现，[weekdays] 为 7 位位图（索引 0=周一 … 6=周日，'1'=出现）；
 * - [REPEAT_ONCE]：只在 [date] 这一天出现。
 *
 * 时间是单个时间点（HH:mm），列表按时间点排序。提醒提前量按事项自定义。
 *
 * @Serializable：仅供日程表单的 rememberSaveable Saver 做进程内快照（旋转屏幕不丢编辑中状态），
 * 不落盘、不参与网络传输。
 */
@Serializable
@Entity(tableName = "todo")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,              // 事项名
    val note: String = "",          // 备注，可空
    val repeatMode: Int = REPEAT_DAILY,
    /** 7 位位图，索引 0=周一 … 6=周日，'1'=该天出现；仅 [REPEAT_WEEKLY] 使用。 */
    val weekdays: String = "0111110",
    /** yyyy-MM-dd；仅 [REPEAT_ONCE] 使用。 */
    val date: String = "",
    /** HH:mm 计划时间点。 */
    val time: String,
    /** 提前提醒分钟数（0 = 到点才提醒）。 */
    val remindMinutes: Int = 15,
    /** 色板下标（与课程共用 CourseColors.basePalette）。 */
    val colorIndex: Int = 0,
    /** 最近一次打卡的日期 yyyy-MM-dd；等于"今天"即视为今日已完成（次日自动复活）。 */
    val lastDoneDate: String = "",
) {
    /** 该事项今天是否已打卡完成。 */
    fun isDoneToday(today: String): Boolean = lastDoneDate == today

    companion object {
        const val REPEAT_DAILY = 0
        const val REPEAT_WEEKLY = 1
        const val REPEAT_ONCE = 2
    }
}
