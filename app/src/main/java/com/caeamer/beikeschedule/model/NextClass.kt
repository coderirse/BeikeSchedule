package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "下一节课"解析：仅取今天（严格教学周内）尚未开始的最早一节课。
 *
 * 口径（用户确认）：
 * - 只看今天——今天课上完就不标记，不跨天指向明天的课；
 * - 正在进行的课不算下一节（已开始的课跳过，取之后最早的一节）；
 * - 仅在今天所属教学周内匹配，假期/开学前/学期后（todayPlan.week=null）与官方放假日
 *   （holiday=true）一律不标记；调休补课日按生效星期匹配。
 */
object NextClass {

    /** 命中的下一节课：courseId 用于与课表卡片（合并后）匹配。 */
    data class Target(
        val courseId: Long,
        val dayOfWeek: Int,
        val week: Int,
        val startTime: String,
    )

    /**
     * @param courses 课表渲染用的合并后课程（与卡片 id 一致，见 CourseMerger）
     * @param sectionStartTimes 小节号 → 开始时间（"08:00"）
     * @param todayPlan 今天的上课计划（[WeekResolver.daySchedule]）：
     *   开学前/假期跳周/学期后为 week=null、官方放假日 holiday=true —— 一律不标记；
     *   调休补课日按 coursesDayOfWeek（生效星期）匹配课程。
     * @param now 当前时间
     */
    fun resolve(
        courses: List<CourseEntity>,
        sectionStartTimes: Map<Int, String>,
        todayPlan: WeekResolver.DaySchedule?,
        now: LocalDateTime,
    ): Target? {
        val week = todayPlan?.week ?: return null
        if (todayPlan.holiday) return null
        val day = todayPlan.coursesDayOfWeek
        val nowTime = now.toLocalTime()
        return courses.asSequence()
            .filter { !it.isUnscheduled && it.dayOfWeek == day && it.hasClassOnWeek(week) }
            .mapNotNull { course ->
                // 节次时间缺失/格式异常时跳过该课，不让它阻断其他候选
                val start = sectionStartTimes[course.startSection]
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    ?: return@mapNotNull null
                if (start.isAfter(nowTime)) course to start else null
            }
            .minByOrNull { it.second }
            ?.let { (course, start) -> Target(course.id, day, week, start.toString()) }
    }
}
