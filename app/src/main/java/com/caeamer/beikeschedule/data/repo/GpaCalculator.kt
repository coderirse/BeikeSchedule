package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.local.GradeEntity

/**
 * 4.0 制 GPA 本地计算（教务网无 4.0 制接口：getgpa.BL 是"平均学分绩/20"口径，会超 4.0）。
 *
 * 换算表（学校权威说明）：90-100=4.0；85-89=3.7；80-84=3.4；75-79=3.0；70-74=2.4；65-69=2.0；60-64=1.0；<60=0。
 * 口径（用户确认）：
 * - 纳入全部有数字成绩的课程（不分必修/选修），等级制（优/良/中/及格）排除；
 * - 同一门课（同 kcdm）出现补考/重修行时，只取补考/重修行（正考未过才有补考），多行取最高分；
 * - 挂科课程计入（绩点 0，学分进分母）。
 */
object GpaCalculator {

    data class GpaResult(
        val gpa: Double,       // 平均绩点（满分 4.0）
        val credits: Double,   // 纳入计算的学分总数
        val courseCount: Int,  // 纳入计算的课程门数
    )

    /** 百分制成绩 → 绩点。 */
    fun gradePoint(score: Double): Double = when {
        score >= 90 -> 4.0
        score >= 85 -> 3.7
        score >= 80 -> 3.4
        score >= 75 -> 3.0
        score >= 70 -> 2.4
        score >= 65 -> 2.0
        score >= 60 -> 1.0
        else -> 0.0
    }

    /** 同一门课只留一行：有补考/重修行取其中最高分，否则取正考最高分。 */
    internal fun bestRowPerCourse(numericGrades: List<GradeEntity>): List<GradeEntity> =
        numericGrades.groupBy { it.kcdm }.map { (_, rows) ->
            val retakes = rows.filter { it.bkcx.isNotBlank() && it.bkcx != "正考" }
            (retakes.ifEmpty { rows }).maxBy { it.numericScore!! }
        }

    /** 计算 4.0 制 GPA；无有效数据返回 null。 */
    fun calculate(grades: List<GradeEntity>): GpaResult? {
        val numeric = grades.filter { it.numericScore != null }
        if (numeric.isEmpty()) return null
        val rows = bestRowPerCourse(numeric)
        val totalCredits = rows.sumOf { it.xf }
        if (totalCredits <= 0.0) return null
        val gpa = rows.sumOf { gradePoint(it.numericScore!!) * it.xf } / totalCredits
        return GpaResult(gpa, totalCredits, rows.size)
    }
}
