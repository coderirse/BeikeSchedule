package com.caeamer.beikeschedule.import.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
                // contentOrNull：显式 null 字段必须落回 ""，用 content 会拿到字面量 "null"
                fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull ?: ""
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
        return runCatching {
            val content = json.parseToJsonElement(jsonText).jsonObject["content"]?.jsonObject
                ?: return null
            // `yqmsxf` 在部分专业/学期会缺失或为 null。**绝不能直接 .jsonObject**：
            // JsonElement.jsonObject 对非对象是抛 IllegalArgumentException，而这个异常
            // 会逃出 uiState 的 combine 变换（stateIn 上游未捕获即崩进程），且坏 JSON
            // 已被 saveCreditMeta 落盘 → 重启后每次进教务页都崩，用户连"清除成绩缓存"
            // 的入口都进不去。字段缺失/形态异常一律当作"没有毕业进度"。
            val yqmsxf = content["yqmsxf"] as? JsonObject ?: return null
            fun num(key: String) = (content[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            fun int(key: String) = (content[key] as? JsonPrimitive)?.intOrNull ?: 0
            val yqxf = (yqmsxf["YQXF"] as? JsonPrimitive)?.doubleOrNull ?: return null
            val yqms = (yqmsxf["YQMS"] as? JsonPrimitive)?.intOrNull ?: 0
            if (yqxf <= 0.0) return null
            GraduationProgress(
                yqxf = yqxf,
                yqms = yqms,
                ywcxf = num("ywcxf"),
                wwcxf = num("wwcxf"),
                ywcms = int("ywcms"),
                wwcms = int("wwcms"),
            )
        }.getOrNull()
    }
}
