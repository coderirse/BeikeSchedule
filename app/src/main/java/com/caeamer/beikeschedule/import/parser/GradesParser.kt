package com.caeamer.beikeschedule.import.parser

import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore.StudentProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** getgpa 接口解析结果（字段语义以实测值为准，详见 docs/GRADES_DESIGN.md）。 */
data class GpaInfo(
    val gpa: Double,          // BL：GPA 值
    val earnedCredits: Double, // HDXF：已获学分
    val passedCourses: Int,   // TGKC：通过课程数
    val rank: Int,            // PM：专业排名
    val totalStudents: Int,   // ZRS：专业总人数
)

/**
 * 成绩接口 JSON → Entity/模型。纯 Kotlin 可单测，
 * fixture 为 2026-08-29 真实会话抓取（docs/samples/grcjcx-all.json、getgpa.json）。
 */
object GradesParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 grcjcx 返回（{code,content:{list:[...]}}）→ 成绩列表。 */
    fun parseGrades(jsonText: String): List<GradeEntity> {
        val list = runCatching {
            json.parseToJsonElement(jsonText).jsonObject["content"]?.jsonObject?.get("list")?.jsonArray
        }.getOrNull() ?: return emptyList()
        return list.mapNotNull { elem ->
            runCatching {
                val o = elem.jsonObject
                fun str(key: String) = o[key]?.jsonPrimitive?.content ?: ""
                GradeEntity(
                    kcdm = str("kcdm"),
                    kcmc = str("kcmc").ifBlank { "未命名课程" },
                    xnxq = str("xnxq"),
                    xnxqmc = str("xnxqmc").ifBlank { str("xnxq") },
                    kcxz = str("kcxz"),
                    kclb = str("kclb"),
                    xf = o["xf"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    zzcj = str("zzcj"),
                    bkcx = str("bkcx"),
                    yxmc = str("yxmc"),
                    sffx = o["sffx"]?.jsonPrimitive?.booleanOrNull ?: false,
                    pm = str("pm"),
                    zrs = str("zrs"),
                    khfs = str("khfs"),
                )
            }.getOrNull()
        }
    }

    /** 解析 getgpa 返回 → GpaInfo；字段缺失或解析失败返回 null。 */
    fun parseGpa(jsonText: String): GpaInfo? {
        val o = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
        fun num(key: String) = o[key]?.jsonPrimitive?.doubleOrNull
        val gpa = num("BL") ?: return null
        val earned = num("HDXF") ?: return null
        val passed = num("TGKC")?.toInt() ?: return null
        val rank = num("PM")?.toInt() ?: return null
        val total = num("ZRS")?.toInt() ?: return null
        return GpaInfo(gpa, earned, passed, rank, total)
    }

    /** 解析 user/me + queryxsxx → 学籍快照；学号缺失返回 null。 */
    fun parseStudentProfile(userJson: String, xsxxJson: String): StudentProfile? {
        val o = runCatching { json.parseToJsonElement(userJson).jsonObject }.getOrNull() ?: return null
        fun str(key: String) = o[key]?.jsonPrimitive?.content ?: ""
        val xh = str("yhdm").ifBlank { str("xh") }
        if (xh.isBlank()) return null
        // 专业名/班级名在 queryxsxx（UserManager/queryxsxx）里；user/me 的 bjzydm 只是代码
        val xsxx = runCatching { json.parseToJsonElement(xsxxJson).jsonObject }.getOrNull()
        fun xs(key: String) = xsxx?.get(key)?.jsonPrimitive?.content.orEmpty()
        val bjmc = xs("BJMC").ifBlank { xs("bjmc") }.ifBlank { str("bjmc") }
        val zymc = xs("ZYMC").ifBlank { xs("zymc") }
        val njmc = xs("NJMC").ifBlank { xs("njmc") }.ifBlank { str("njmc") }
        return StudentProfile(
            xm = str("xm"),
            xh = xh,
            yxmc = str("bmmc"),
            zymc = zymc.ifBlank { str("zymc") },
            bjmc = bjmc,
            njmc = njmc,
            xjsfzx = str("sfzx"),
            xjsfzc = str("sfzc"),
        )
    }

    /** 版本号比较：a > b 返回正数（逐段数字比较，段数不齐按 0 补）。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.removePrefix("v").removePrefix("V").split(".")
        val pb = b.removePrefix("v").removePrefix("V").split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }
}
