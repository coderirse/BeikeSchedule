package com.caeamer.beikeschedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 教务成绩条目（来自 /cjgl/grcjcx/grcjcx，覆盖式全量刷新）。 */
@Entity(tableName = "grade")
data class GradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kcdm: String,      // 课程代码
    val kcmc: String,      // 课程名
    val xnxq: String,      // 学期代码，如 2025-20262
    val xnxqmc: String,    // 学期名，如 2025-2026-2
    val kcxz: String,      // 课程性质：必修/任选…
    val kclb: String,      // 课程类别：通识课程/实验/专业核心…
    val xf: Double,        // 学分
    val zzcj: String,      // 总评成绩（原始字符串，含非数字如"优"/"良"）
    val bkcx: String,      // 考试类型：正考 / 补考 / 重修（见 GpaCalculatorTest 的用例）
    val yxmc: String,      // 开课学院
    val sffx: Boolean,     // 是否辅修
    val pm: String = "",   // 该课排名（原始字符串，""=无/等级制）
    val zrs: String = "",  // 该课程总人数
    val khfs: String = "", // 考核方式（考试/考查）
) {
    /**
     * 数字成绩；非数字成绩（等级制）返回 null。
     *
     * 必须同时限定有限性与合理区间：`"NaN"` / `"Infinity"` 能被 `toDoubleOrNull` 解析成功，
     * 而 IEEE 比较语义下 `NaN < 60` 与 `NaN >= 60` **同时为 false** —— 于是这样的行
     * `isFailed` 与 `isPassed` 都是 false，成为一个静默的第三态，还会污染 GPA/加权求和。
     * 区间上界 150 用于挡住明显的脏数据（百分制不存在 >150 的总评）。
     */
    val numericScore: Double?
        get() = zzcj.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..150.0 }

    /** 是否不及格（仅对数字成绩判定；等级制不标红）。 */
    val isFailed: Boolean get() = (numericScore?.let { it < 60 } == true)

    /**
     * 是否已通过：数字 ≥60，或等级制 优/良/中/及格/合格/通过（学分汇总口径，与教务网页一致）。
     *
     * "通过" 必列：实测成绩单里 `军训`(2 学分)、`新时代大学生国家安全教育`(1 学分)、
     * `大学生公共安全教育`(0 学分) 的总评就是 "通过"，漏判会让学分类别进度少算学分
     * （教务网 getgpa 的 HDXF/TGKC 把这些行计入通过）。
     */
    val isPassed: Boolean
        get() = numericScore?.let { it >= 60 } == true ||
            zzcj in setOf("优", "良", "中", "及格", "合格", "通过")
}
