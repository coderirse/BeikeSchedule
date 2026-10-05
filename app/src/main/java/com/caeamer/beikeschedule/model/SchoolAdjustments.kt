package com.caeamer.beikeschedule.model

import java.time.LocalDate

/**
 * 校历调休补课的**内置数据**（编译进 App，随版本发布更新）。
 *
 * 教务系统里查不到补课安排（课表无周末行、教务通知系统无放假通知——2026-10 实抓验证），
 * 「某日按周几上课」只能来自教务处/班级通知。此前在学期设置里手动登记，已按产品决策移除：
 * 每学期教务处发布调课通知后，把映射编入本表随新版本推送，用户零操作。
 *
 * 键 = 学年学期 "xn-xq"（与 SemesterConfig.xn/xq 对应，如 "2026-2027-1"）；
 * 值 = 补课日 → 生效星期（1..7，该日按所在教学周的这个星期的课表上课）。
 */
object SchoolAdjustments {

    private val BAKED: Map<String, Map<LocalDate, Int>> = mapOf(
        // 2026 国庆调课（教务处通知口径）：10/10（周六，第 4 周）补周三的课
        "2026-2027-1" to mapOf(
            LocalDate.of(2026, 10, 10) to 3,
        ),
    )

    /** 指定学期的内置补课映射；没有内置数据的学期返回空表。 */
    fun makeupsFor(xn: String, xq: String): Map<LocalDate, Int> =
        BAKED["$xn-$xq"].orEmpty()
}
