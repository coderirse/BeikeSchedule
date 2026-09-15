package com.caeamer.beikeschedule.model

import com.caeamer.beikeschedule.data.local.CourseEntity

/**
 * 上课提醒的课程筛选（纯函数，便于单测）。
 *
 * - 排除无固定时间课程（没有节次，排不出时间点）；
 * - 排除已隐藏课程（用户主动隐藏即不希望它出现，也不该再提醒）；
 * - 合并教务拆出的同名同段多行——否则同一节课会因多行各排一个闹钟而重复弹两次。
 */
object ReminderCourses {

    fun eligible(all: List<CourseEntity>): List<CourseEntity> =
        CourseMerger.mergeSameSlot(all.filter { !it.isUnscheduled && !it.hidden })
}
