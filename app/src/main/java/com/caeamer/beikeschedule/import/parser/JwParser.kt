package com.caeamer.beikeschedule.import.parser

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.model.SectionMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 北科本研一体化教务系统（byyt.ustb.edu.cn）JSON → Entity 映射。
 * 纯 Kotlin 实现，不依赖 Android，可直接 JUnit 单测。
 * 接口与字段定义见 docs/TECH_DESIGN.md 2.1 节，样本见 docs/samples/。
 *
 * 注意：取字符串一律用 `contentOrNull`。`JsonNull` 本身是 `JsonPrimitive`，
 * 其 `content` 返回字面量字符串 "null"，会让 `?: ""` / `?: return null` 全部失效 ——
 * 例如 `"KEY":null` 会得到 "null" 而非 null，`parseDayOfWeek` 抛异常后整行课程被静默丢弃。
 */
object JwParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 行级解析失败日志钩子。本对象不依赖 Android（需可 JVM 单测），App 入口
     * （JwImportBridge）把它接到 android.util.Log：教务改字段格式导致整行/整表
     * 被静默跳过时，logcat 里有据可查，而不是用户只看到空课表。
     */
    var rowErrorLogger: ((row: String, error: Throwable) -> Unit)? = null

    /** 解析 /xszykb/queryxszykbzong 返回（顶层为 JSON 数组）。 */
    fun parseCourses(jsonText: String): List<CourseEntity> {
        val root = json.parseToJsonElement(jsonText).jsonArray
        return root.mapNotNull { elem ->
            runCatching { toCourse(elem.jsonObject) }
                .onFailure { rowErrorLogger?.invoke(elem.toString().take(300), it) }
                .getOrNull()
        }
    }

    /** 解析 /component/queryKbjg 返回（{code, content:[...]}），取节次时间。 */
    fun parseSectionTimes(jsonText: String): List<SectionTimeEntity> {
        val content = json.parseToJsonElement(jsonText).jsonObject["content"]?.jsonArray
            ?: return emptyList()
        return content.mapNotNull { elem ->
            val obj = elem.jsonObject
            val section = obj["xj"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            // 显式 null 必须落空跳过（用 content 会拿到 "null" 并被当成合法时间存库）
            val start = obj["kssj"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val end = obj["jssj"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SectionTimeEntity(section = section, startTime = start, endTime = end)
        }.sortedBy { it.section }
    }

    /** 解析 /component/querydangqianxnxq 返回：学年、学期、学期展示名。 */
    fun parseCurrentSemester(jsonText: String): Triple<String, String, String> {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        val xn = obj["XN"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val xq = obj["XQ"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val name = obj["XNXQ"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return Triple(xn, xq, name)
    }

    /** 解析 /component/queryRlZcSj 返回：取 xqj=1（周一）的 rq 日期作为第 1 周周一。 */
    fun parseFirstMonday(jsonText: String): String? {
        val content = json.parseToJsonElement(jsonText).jsonObject["content"]?.jsonArray
            ?: return null
        return content.map { it.jsonObject }
            .firstOrNull { it["xqj"]?.jsonPrimitive?.contentOrNull == "1" }
            ?.get("rq")?.jsonPrimitive?.contentOrNull
    }

    /**
     * 教学周日历解析结果。
     * @param weekMondays 下标+1 = 教学周，值 = 该周周一（yyyy-MM-dd）
     * @param totalWeeks 学期总教学周数
     */
    data class WeekCalendar(val weekMondays: List<String>, val totalWeeks: Int)

    /**
     * 解析导入脚本产出的统一周历 JSON：{"totalWeeks":18, "weeks":[{"zc":1,"monday":"2026-09-07"},...]}。
     * weeks 为空或解析失败时 totalWeeks 回退 0，由上层决定是否保留手工配置。
     */
    fun parseWeekCalendar(jsonText: String): WeekCalendar {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return WeekCalendar(emptyList(), 0)
        val totalWeeks = root["totalWeeks"]?.jsonPrimitive?.intOrNull ?: 0
        val weeks = root["weeks"]?.jsonArray?.mapNotNull { elem ->
            val obj = elem.jsonObject
            val zc = obj["zc"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val monday = obj["monday"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            zc to monday
        }?.sortedBy { it.first } ?: emptyList()
        // 按 zc 顺序展开为下标列表，zc 必须从 1 开始；中间缺失的周用前一周 +7 天补齐（防御性）
        if (weeks.isEmpty() || weeks.first().first != 1) return WeekCalendar(emptyList(), totalWeeks)
        // 脏数据防御 1：zc 是教务/脚本产出的自由数字，脏值（如 1e5）会生成十万级列表
        if (weeks.last().first > MAX_TOTAL_WEEKS) return WeekCalendar(emptyList(), totalWeeks)
        // 脏数据防御 2：monday 原样入库后，WeekResolver 对坏串 parse 失败会"用最后一个
        // 已知周一倒推"，其后所有周整体错位——日期串必须先验证合法
        if (weeks.any { runCatching { java.time.LocalDate.parse(it.second) }.isFailure }) {
            rowErrorLogger?.invoke("weekCalendar monday invalid", IllegalArgumentException("monday 格式异常"))
            return WeekCalendar(emptyList(), totalWeeks)
        }
        val mondays = arrayListOf<String>()
        var lastMonday = ""
        for (i in 1..weeks.last().first) {
            val found = weeks.firstOrNull { it.first == i }?.second
            lastMonday = when {
                found != null -> found
                lastMonday.isNotEmpty() -> runCatching {
                    java.time.LocalDate.parse(lastMonday).plusWeeks(1).toString()
                }.getOrDefault("")
                else -> ""
            }
            if (lastMonday.isEmpty()) return WeekCalendar(emptyList(), totalWeeks)
            mondays += lastMonday
        }
        return WeekCalendar(mondays, totalWeeks)
    }

    private fun toCourse(obj: JsonObject): CourseEntity {
        val sksj = obj["SKSJ"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val key = obj["KEY"]?.jsonPrimitive?.contentOrNull
        val colorIndex = obj["XB"]?.jsonPrimitive?.intOrNull ?: 0
        // KEY="bz" 为教务备注行（实验/上机安排等），也归入无固定时间课程
        val unscheduled = key == "bz" || key.isNullOrBlank() || colorIndex == CourseEntity.COLOR_UNSCHEDULED

        val dayOfWeek = if (unscheduled) 0 else parseDayOfWeek(key!!)
        // 小节：教务"单周调课行"没有 KSJC/JSJC（null），只有 KEY 的 jc 段标明调课到哪一大节
        // （如 xq3_jc1=1-2节），此时从 KEY 推导，避免解析成 0 导致渲染成错位细条
        val keyRange = key?.let { keySectionRange(it) }
        val startSection = if (unscheduled) 0 else obj["KSJC"]?.jsonPrimitive?.intOrNull ?: keyRange?.first ?: 0
        val endSection = if (unscheduled) 0 else obj["JSJC"]?.jsonPrimitive?.intOrNull ?: keyRange?.second ?: startSection
        // 节次全缺且 KEY 也没有 jc 段时兜底出的 0 会让编辑框把课程映射到第六大节
        // （SectionMap.bigIndexOf(0) → 11-12 节），用户一保存课程就被永久搬走——
        // 宁可整行跳过并留日志，也不要产出必错的行
        if (!unscheduled && startSection <= 0) {
            throw IllegalArgumentException("课程行缺失节次: key=$key SKSJ=$sksj")
        }

        val (name, teacher, location) = splitSksj(sksj, unscheduled)

        var weekBitmap = obj["ZC"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // 备注行没有 ZC 字段，从文本中的周数描述（如 "5-7周"、"15,16周"）构造位图
        if (unscheduled && weekBitmap.isEmpty()) {
            weekBitmap = parseNoteWeeks(sksj)
        }

        return CourseEntity(
            taskId = obj["RWH"]?.jsonPrimitive?.contentOrNull.orEmpty(),
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

    /** KEY 的 jc 段 → 该大节的小节区间（jc=1 → 1..2，jc=6 → 11..12）；无法识别返回 null。 */
    internal fun keySectionRange(key: String): Pair<Int, Int>? =
        Regex("jc(\\d+)").find(key)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..SectionMap.BIG_SECTIONS.size }
            ?.let { big -> SectionMap.BIG_SECTIONS[big - 1].let { it.first to it.last } }

    /** 周历 zc 合理上限（真实学期 ≤ 30 周，放一倍余量），超过按脏数据整体回退。 */
    private const val MAX_TOTAL_WEEKS = 60

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

    /** KEY 形如 "xq2_jc1"，提取星期 N（1..7）；越界（xq0/xq8 等）视为脏数据抛出整行跳过——
     *  否则 dayOfWeek=8 的行在任何周布局里都不可见，整门课静默消失。 */
    internal fun parseDayOfWeek(key: String): Int {
        val match = Regex("^xq(\\d)_jc\\d+$").find(key)
            ?: throw IllegalArgumentException("无法识别的 KEY: $key")
        return match.groupValues[1].toInt().takeIf { it in 1..7 }
            ?: throw IllegalArgumentException("非法星期（须 1..7）: $key")
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
        // 教师不能按固定行号取：某行缺教师时 lines[1] 实际是周数行，教师栏会显示 "1-16周"。
        // 规则：跳过周数行（"1-16周"）、地点行（【校区】开头）、节次行（"第X-Y节"）
        val isWeeksLine = Regex("^[\\d,\\-]+周$")
        val teacher = lines.drop(1).firstOrNull {
            !isWeeksLine.matches(it) && !it.startsWith("【") && !Regex("^第\\d+").matches(it)
        } ?: ""
        // 地点优先认【校区】前缀；没有时兜底取最后一个非周数/非教师/非节次行
        val location = lines.firstOrNull { it.startsWith("【") }
            ?: lines.lastOrNull { it != name && it != teacher && !isWeeksLine.matches(it) && !Regex("^第\\d+").matches(it) }
                .orEmpty()
        return Triple(name, teacher, location)
    }
}
