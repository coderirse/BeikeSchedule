package com.caeamer.beikeschedule.data.remote

import com.caeamer.beikeschedule.data.local.CourseEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 班级实验/上机安排——教务系统**拿不到**的数据：任课老师自己排好、在班级群里通知
 * （教务里这些课只有"无固定时间课程"条目）。数据放服务端（`bs-class-schedules.json`），
 * 每次「登录并同步」按班级下发并整组替换，改动只需更新服务端、不用发版。
 *
 * 服务端按"学年-学期 + 学籍班级名"匹配：未匹配到本班时 [sessions] 为空，
 * 其他班的账号看不到本班安排。
 */
@Serializable
data class ClassSchedule(
    val xn: String = "",
    val xq: String = "",
    /** 命中的班级键（服务端配置里写的那个，如"机243"）；未命中为空串。 */
    @SerialName("class") val className: String = "",
    val sessions: List<ClassSession> = emptyList(),
    /** 本班已有真实时间的课程名片段：命中且仍是"无固定时间课程"的教务导入行会被自动隐藏。 */
    val hideUnscheduledContaining: List<String> = emptyList(),
)

/** 一场实验/上机。字段语义与服务端 `bs-class-schedules.json` 一致。 */
@Serializable
data class ClassSession(
    /** 稳定 id：兼作课程行 taskId，整组替换时按它保留用户对该行的隐藏状态。 */
    val id: String = "",
    val name: String = "",
    /** 1..7（周一..周日）。 */
    val dayOfWeek: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
    /** 有课的周次（1 起，口径与校历周次一致）。 */
    val weeks: List<Int> = emptyList(),
    val location: String = "",
    /** 备注（如"8:30 到岗"），课程详情里原样展示。 */
    val note: String = "",
    val colorIndex: Int = 0,
)

/** 服务端场次 → 课程行（纯函数，JVM 可单测）。 */
object ClassScheduleMapper {

    private const val WEEK_BITMAP_LENGTH = 32 // 与教务 ZC 位图同构：index 0 恒 '0' 占位

    /** 脏数据（空名/星期越界）逐条丢弃，坏一条不影响其余场次。 */
    fun toCourses(sessions: List<ClassSession>): List<CourseEntity> = sessions.mapNotNull { session ->
        val name = session.name.trim()
        if (name.isEmpty() || session.dayOfWeek !in 1..7) return@mapNotNull null
        val start = session.startSection.coerceAtLeast(1)
        CourseEntity(
            id = 0,
            // 服务端 id 缺失时用内容合成稳定键：同一场次跨次同步仍能保持"隐藏"状态
            taskId = session.id.trim().ifBlank { "lab:$name:${session.dayOfWeek}:$start" },
            name = name,
            teacher = "",
            location = session.location.trim(),
            dayOfWeek = session.dayOfWeek,
            startSection = start,
            endSection = session.endSection.coerceAtLeast(start),
            weekBitmap = weekBitmapOf(session.weeks),
            colorIndex = session.colorIndex,
            source = CourseEntity.SOURCE_LAB,
            note = session.note.trim(),
        )
    }

    /** 周次位图：第 i 周对应 index i，'1' = 有课（越过总周数的位由 UI 侧自然忽略）。 */
    internal fun weekBitmapOf(weeks: List<Int>): String =
        CharArray(WEEK_BITMAP_LENGTH) { i -> if (i in weeks) '1' else '0' }.concatToString()
}
