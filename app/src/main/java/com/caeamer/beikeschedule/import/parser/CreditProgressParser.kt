package com.caeamer.beikeschedule.import.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 学业完成情况解析（cjgl/cjzhtjcx/cjcx 模块，均为包装响应 {code,msg,content}）。
 * - queryXflbyq → 学分类别要求表（要求学分口径实锤；其 ywcxf 是转移口径，不在此解析）
 * - queryBxkqk  → 毕业总进度
 * "已完成学分"由 App 按成绩单 kclb 本地汇总（CreditAggregator），与教务网页口径一致。
 */
object CreditProgressParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 学分类别要求（仅保留要求学分 >0 的行）。 */
    data class CreditCategory(
        val kclbmc: String,   // 学分类别（通识课程/学科平台/素质拓展—美育(素质拓展)…）
        val kcxzmc: String,   // 课程性质（必修/限选/任选）
        val yqxf: Double,     // 要求学分
        val yzhxf: Double,    // 已转移学分
        val dzhxf: Double,    // 待转移学分
    )

    /** 毕业总进度。 */
    data class GraduationProgress(
        val yqxf: Double,     // 要求学分
        val yqms: Int,        // 要求门数
        val ywcxf: Double,    // 已修学分
        val wwcxf: Double,    // 未完成学分
        val ywcms: Int,       // 已过门数
        val wwcms: Int,       // 未过门数
    )

    fun parseCategories(jsonText: String): List<CreditCategory> {
        if (jsonText.isBlank()) return emptyList()
        val list = runCatching {
            json.parseToJsonElement(jsonText).jsonObject["content"]?.jsonObject?.get("list")?.jsonArray
        }.getOrNull() ?: return emptyList()
        return list.mapNotNull { elem ->
            runCatching {
                val o = elem.jsonObject
                fun str(key: String) = o[key]?.jsonPrimitive?.content ?: ""
                fun num(key: String) = o[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val yqxf = num("yqwcxf")
                if (yqxf <= 0.0) return@runCatching null
                CreditCategory(
                    kclbmc = str("kclbmc"),
                    kcxzmc = str("kcxzmc"),
                    yqxf = yqxf,
                    yzhxf = num("yzhxf"),
                    dzhxf = num("dzhxf"),
                )
            }.getOrNull()
        }
    }

    fun parseProgress(jsonText: String): GraduationProgress? {
        if (jsonText.isBlank()) return null
        val o = runCatching {
            json.parseToJsonElement(jsonText).jsonObject["content"]?.jsonObject
        }.getOrNull() ?: return null
        fun num(key: String) = o[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
        fun int(key: String) = o[key]?.jsonPrimitive?.intOrNull ?: 0
        val yqxf = o["yqmsxf"]?.jsonObject?.get("YQXF")?.jsonPrimitive?.doubleOrNull ?: return null
        val yqms = o["yqmsxf"]?.jsonObject?.get("YQMS")?.jsonPrimitive?.intOrNull ?: 0
        if (yqxf <= 0.0) return null
        return GraduationProgress(
            yqxf = yqxf,
            yqms = yqms,
            ywcxf = num("ywcxf"),
            wwcxf = num("wwcxf"),
            ywcms = int("ywcms"),
            wwcms = int("wwcms"),
        )
    }
}
