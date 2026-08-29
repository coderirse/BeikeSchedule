package com.caeamer.beikeschedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 个人考试安排（来自 /kscxtj/queryXsksByxhList，覆盖式全量刷新，仅当前学期）。
 * 字段来自列定义 JS（docs/samples/XskscxByXhColumn.js）；时间从 KSSJMS 描述解析，解析失败留空。
 */
@Entity(tableName = "exam")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kcdm: String,      // 课程代码
    val kcmc: String,      // 课程名
    val kslx: String,      // 考试类型（KSSJDMC，如"期末考试"）
    val kssjms: String,    // 考试时间描述原文（KSSJMS）
    val ksrq: String,      // 解析出的日期 yyyy-MM-dd，""=未解析出
    val kssj: String,      // 开始时间 HH:mm，""=未解析出
    val jssj: String,      // 结束时间 HH:mm，""=未解析出
    val cdmc: String,      // 地点（CDXX 优先，CDDM 兜底）
    val zwh: String,       // 座位号
    val jkjsbz: String,    // 进考场标志/备注
    val kkyxmc: String,    // 开课学院
    val xnxq: String,      // 学期代码（冗余，便于覆盖刷新与过滤）
) {
    /** 考试是否已有可用的日期（用于排期与倒计时）。 */
    val hasDate: Boolean get() = ksrq.isNotBlank()
}
