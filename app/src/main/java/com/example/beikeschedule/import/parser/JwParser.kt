package com.example.beikeschedule.import.parser

import com.example.beikeschedule.data.local.CourseEntity
import com.example.beikeschedule.data.local.SectionTimeEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 北科本研一体化教务系统（byyt.ustb.edu.cn）JSON → Entity 映射。
 * 纯 Kotlin 实现，不依赖 Android，可直接 JUnit 单测。
 * 接口与字段定义见 docs/TECH_DESIGN.md 2.1 节，样本见 docs/samples/。
 */
object JwParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 /xszykb/queryxszykbzong 返回（顶层为 JSON 数组）。 */
    fun parseCourses(jsonText: String): List<CourseEntity> {
        val root = json.parseToJsonElement(jsonText).jsonArray
        return root.mapNotNull { elem ->
            runCatching { toCourse(elem.jsonObject) }.getOrNull()
        }
    }

    /** 解析 /component/queryKbjg 返回（{code, content:[...]}），取节次时间。 */
    fun parseSectionTimes(jsonText: String): List<SectionTimeEntity> {
        val content = json.parseToJsonElement(jsonText).jsonObject["content"]?.jsonArray
            ?: return emptyList()
        return content.mapNotNull { elem ->
            val obj = elem.jsonObject
            val section = obj["xj"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            val start = obj["kssj"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val end = obj["jssj"]?.jsonPrimitive?.content ?: return@mapNotNull null
            SectionTimeEntity(section = section, startTime = start, endTime = end)
        }.sortedBy { it.section }
    }

    /** 解析 /component/querydangqianxnxq 返回：学年、学期、学期展示名。 */
    fun parseCurrentSemester(jsonText: String): Triple<String, String, String> {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        val xn = obj["XN"]?.jsonPrimitive?.content.orEmpty()
        val xq = obj["XQ"]?.jsonPrimitive?.content.orEmpty()
        val name = obj["XNXQ"]?.jsonPrimitive?.content.orEmpty()
        return Triple(xn, xq, name)
    }

    /** 解析 /component/queryRlZcSj 返回：取 xqj=1（周一）的 rq 日期作为第 1 周周一。 */
    fun parseFirstMonday(jsonText: String): String? {
        val content = json.parseToJsonElement(jsonText).jsonObject["content"]?.jsonArray
            ?: return null
        return content.map { it.jsonObject }
            .firstOrNull { it["xqj"]?.jsonPrimitive?.content == "1" }
            ?.get("rq")?.jsonPrimitive?.content
    }

    private fun toCourse(obj: JsonObject): CourseEntity {
        val sksj = obj["SKSJ"]?.jsonPrimitive?.content.orEmpty()
        val key = obj["KEY"]?.jsonPrimitive?.content
        val colorIndex = obj["XB"]?.jsonPrimitive?.intOrNull ?: 0
        // KEY="bz" 为教务备注行（实验/上机安排等），也归入无固定时间课程
        val unscheduled = key == "bz" || key.isNullOrBlank() || colorIndex == CourseEntity.COLOR_UNSCHEDULED

        val dayOfWeek = if (unscheduled) 0 else parseDayOfWeek(key!!)
        val startSection = if (unscheduled) 0 else obj["KSJC"]?.jsonPrimitive?.intOrNull ?: 0
        val endSection = if (unscheduled) 0 else obj["JSJC"]?.jsonPrimitive?.intOrNull ?: startSection

        val (name, teacher, location) = splitSksj(sksj, unscheduled)

        var weekBitmap = obj["ZC"]?.jsonPrimitive?.content.orEmpty()
        // 备注行没有 ZC 字段，从文本中的周数描述（如 "5-7周"、"15,16周"）构造位图
        if (unscheduled && weekBitmap.isEmpty()) {
            weekBitmap = parseNoteWeeks(sksj)
        }

        return CourseEntity(
            taskId = obj["RWH"]?.jsonPrimitive?.content.orEmpty(),
            name = name,
            teacher = teacher,
            location = location,
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            endSection = endSection,
            weekBitmap = weekBitmap,
            colorIndex = if (unscheduled) CourseEntity.COLOR_UNSCHEDULED else colorIndex,
            source = CourseEntity.SOURCE_IMPORT,
        )
    }

    /** 从备注文本解析周数（"机械设计 5-7周"、"微机原理与应用B 15,16周"），生成长度 34 的位图。 */
    internal fun parseNoteWeeks(sksj: String): String {
        val m = Regex("([\\d,\\-]+)周").find(sksj) ?: return ""
        val weeks = mutableSetOf<Int>()
        m.groupValues[1].split(",").forEach { part ->
            val range = part.split("-")
            val a = range.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val b = range.getOrNull(1)?.toIntOrNull() ?: a
            weeks += a..b
        }
        if (weeks.isEmpty()) return ""
        val sb = StringBuilder("0")
        for (w in 1..33) sb.append(if (w in weeks) '1' else '0')
        return sb.toString()
    }

    /** KEY 形如 "xq2_jc1"，提取星期 N（1..7）。 */
    internal fun parseDayOfWeek(key: String): Int {
        val match = Regex("^xq(\\d)_jc\\d+$").find(key)
            ?: throw IllegalArgumentException("无法识别的 KEY: $key")
        return match.groupValues[1].toInt()
    }

    /**
     * 拆分 SKSJ 多行文本。
     * 有固定时间："课程名\n教师\n周数\n【校区】地点\n第X-Y节"
     * 无固定时间："课程名 [1-16周] 教师 备注:无"（单行）
     */
    internal fun splitSksj(sksj: String, unscheduled: Boolean): Triple<String, String, String> {
        if (sksj.isBlank()) return Triple("未命名课程", "", "")
        if (unscheduled) {
            // 备注行格式：机械设计 5-7周 【实验】
            Regex("^(.*?)\\s+[\\d,\\-]+周\\s*(【[^】]*】)?\\s*$").find(sksj)?.let { m ->
                val type = m.groupValues.getOrElse(2) { "" }
                return Triple(m.groupValues[1].trim() + type, "", "")
            }
            // 单行格式：电子技术实验 [1-16周] 木春梅 备注:无
            val m = Regex("^(.*?)\\s*\\[.*?]\\s*(.*?)\\s*备注.*$").find(sksj)
            return if (m != null) {
                Triple(m.groupValues[1].trim(), m.groupValues[2].trim(), "")
            } else {
                Triple(sksj.trim(), "", "")
            }
        }
        val lines = sksj.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = lines.getOrElse(0) { "未命名课程" }
        val teacher = lines.getOrElse(1) { "" }
        val location = lines.firstOrNull { it.startsWith("【") }.orEmpty()
        return Triple(name, teacher, location)
    }
}
