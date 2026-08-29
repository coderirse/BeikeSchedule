package com.caeamer.beikeschedule.import.parser

import com.caeamer.beikeschedule.data.local.ExamEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 考试安排解析（/kscxtj/queryXsksByxhList，裸 PageHelper 分页 {total,list:[...]}）。
 * 字段名为大写（来自列定义 JS，见 docs/samples/XskscxByXhColumn.js）。
 * KSSJMS 是时间描述文本，日期/起止时间用正则防御性解析，解析失败留空、UI 回退显示原文。
 */
object ExamsParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 时间描述里的日期+可选起止时间，如 "2027-01-15 08:00~09:50"、"2027/1/15 8:00-9:50"。 */
    private val TIME_REGEX = Regex(
        "(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?" +
            "(?:[^0-9]{0,3}(\\d{1,2}):(\\d{2}))?" +
            "(?:[^0-9]{1,4}(\\d{1,2}):(\\d{2}))?",
    )

    fun parseExams(jsonText: String, xnxq: String): List<ExamEntity> {
        if (jsonText.isBlank()) return emptyList()
        val list = runCatching {
            json.parseToJsonElement(jsonText).jsonObject["list"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return list.mapNotNull { elem ->
            runCatching {
                val o = elem.jsonObject
                fun str(key: String) = o[key]?.jsonPrimitive?.content ?: ""
                val kssjms = str("KSSJMS")
                val (date, start, end) = parseExamTime(kssjms)
                ExamEntity(
                    kcdm = str("KCDM"),
                    kcmc = str("KCMC").ifBlank { "未命名课程" },
                    kslx = str("KSSJDMC"),
                    kssjms = kssjms,
                    ksrq = date,
                    kssj = start,
                    jssj = end,
                    cdmc = str("CDXX").ifBlank { str("CDDM") },
                    zwh = str("ZWH"),
                    jkjsbz = str("JKJSBZ"),
                    kkyxmc = str("KKYXMC"),
                    xnxq = xnxq,
                )
            }.getOrNull()
        }
    }

    /** 从时间描述解析 (yyyy-MM-dd, HH:mm, HH:mm)；解析不出返回空串三元组。 */
    internal fun parseExamTime(text: String): Triple<String, String, String> {
        val m = TIME_REGEX.find(text) ?: return Triple("", "", "")
        val (y, mo, d) = Triple(
            m.groupValues[1],
            m.groupValues[2].padStart(2, '0'),
            m.groupValues[3].padStart(2, '0'),
        )
        val startH = m.groupValues[4]
        val start = if (startH.isEmpty()) "" else startH.padStart(2, '0') + ":" + m.groupValues[5]
        val endH = m.groupValues[6]
        val end = if (endH.isEmpty()) "" else endH.padStart(2, '0') + ":" + m.groupValues[7]
        return Triple("$y-$mo-$d", start, end)
    }
}
