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
    val bkcx: String,      // 正考/补考
    val yxmc: String,      // 开课学院
    val sffx: Boolean,     // 是否辅修
    val pm: String = "",   // 该课排名（原始字符串，""=无/等级制）
    val zrs: String = "",  // 该课程总人数
    val khfs: String = "", // 考核方式（考试/考查）
) {
    /** 数字成绩；非数字成绩（等级制）返回 null。 */
    val numericScore: Double? get() = zzcj.toDoubleOrNull()

    /** 是否不及格（仅对数字成绩判定；等级制不标红）。 */
    val isFailed: Boolean get() = (numericScore?.let { it < 60 } == true)

    /** 是否已通过：数字 ≥60，或等级制 优/良/中/及格/合格（学分汇总口径，与教务网页一致）。 */
    val isPassed: Boolean
        get() = numericScore?.let { it >= 60 } == true ||
            zzcj in setOf("优", "良", "中", "及格", "合格")
}
