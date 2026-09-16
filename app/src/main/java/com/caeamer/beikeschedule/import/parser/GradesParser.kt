package com.caeamer.beikeschedule.import.parser

import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore.StudentProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** getgpa 接口解析结果（字段语义以实测值为准，详见 docs/GRADES_DESIGN.md）。 */
data class GpaInfo(
    val gpa: Double,          // BL：GPA 值
    val earnedCredits: Double, // HDXF：已获学分
    val passedCourses: Int,   // TGKC：通过课程数
    /**
     * PM：专业排名。**可空**：学期初排名未生成、未排名专业、转专业首学期等情况下
     * 接口不返回该字段。此前它是非空字段，任何一个缺失就让整个 GpaInfo 变成 null，
     * 于是"平均学分绩 + 已获学分"明明有值，GPA 卡片却整块显示"—"。
     */
    val rank: Int? = null,
    /** ZRS：专业总人数。可空，理由同 [rank]；为 0 时也应视为"无排名"。 */
    val totalStudents: Int? = null,
) {
    /** 排名信息是否可用（两个字段都有且总人数为正）。 */
    val hasRank: Boolean
        get() = rank != null && totalStudents != null && totalStudents > 0
}

/**
 * 成绩接口 JSON → Entity/模型。纯 Kotlin 可单测，
 * fixture 为 2026-08-29 真实会话抓取（docs/samples/grcjcx-all.json、getgpa.json）。
 *
 * 注意：取字符串一律用 `contentOrNull`，不能用 `content`。
 * `JsonNull` 本身是 `JsonPrimitive`，其 `content` 返回字面量字符串 "null"，
 * 于是 `o[key]?.jsonPrimitive?.content ?: ""` 的 `?:` 兜底永远不会触发 ——
 * 真实成绩单里 `"pm":null` / `"khfs":null` / `"kclb":null` 会原样渲染成"排名 null/96"。
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
                fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull ?: ""
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

    /**
     * 解析 getgpa 返回 → GpaInfo；解析失败返回 null。
     *
     * 只有 BL（平均学分绩）与 HDXF（已获学分）是必需的：这两个没有就无法展示任何 GPA 信息。
     * 其余字段一律可缺省——尤其 PM/ZRS（专业排名/总人数）在学期初、未排名专业、转专业
     * 首学期都可能缺失，此前缺失会让整个卡片消失。TGKC 缺失按 0 计（只影响一行文案）。
     */
    fun parseGpa(jsonText: String): GpaInfo? {
        val o = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
        fun num(key: String) = o[key]?.jsonPrimitive?.doubleOrNull
        val gpa = num("BL") ?: return null
        val earned = num("HDXF") ?: return null
        val passed = num("TGKC")?.toInt() ?: 0
        val rank = num("PM")?.toInt()
        val total = num("ZRS")?.toInt()
        return GpaInfo(gpa, earned, passed, rank, total)
    }

    /** 解析 user/me + queryxsxx → 学籍快照；学号缺失返回 null。 */
    fun parseStudentProfile(userJson: String, xsxxJson: String): StudentProfile? {
        val o = runCatching { json.parseToJsonElement(userJson).jsonObject }.getOrNull() ?: return null
        fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull ?: ""
        val xh = str("yhdm").ifBlank { str("xh") }
        if (xh.isBlank()) return null
        // 专业名/班级名在 queryxsxx（UserManager/queryxsxx）里；user/me 的 bjzydm 只是代码
        val xsxx = runCatching { json.parseToJsonElement(xsxxJson).jsonObject }.getOrNull()
        fun xs(key: String) = xsxx?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
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
