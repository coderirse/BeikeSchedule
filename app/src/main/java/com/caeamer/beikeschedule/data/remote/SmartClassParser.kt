package com.caeamer.beikeschedule.data.remote

import org.json.JSONObject

/**
 * 贝壳教学平台（smartclass）无课教室的数据模型与解析。
 *
 * 响应结构（2026-09 实测）：
 * ```json
 * // /general/api/open/building/listBuildings
 * {"code":0,"msg":"success","data":[{"id":"1cd73be1…","name":"教学楼"}]}
 * // /general/api/open/teachingCycle/listNodeTypes
 * {"code":0,"msg":"success","data":[{"id":"49c16693…","name":"默认节次"}]}
 * // /general/api/classroom/freeClassRooms
 * {"code":0,"msg":"success","data":[{
 *     "nodeId":"f334c476…","nodeName":"第一大节",
 *     "startTime":"2000-01-01 07:58:00","endTime":"2000-01-01 09:35:00",
 *     "classroomItems":[{"classroomId":26353,"classroomName":"教学楼503",
 *                        "noSeatRate":0.9,"seatCount":96}]}]}
 * ```
 *
 * 解析一律用 `optX` 而非 `getX`：服务端字段缺失/改类型时 `getX` 会抛异常，
 * 让整个页面白屏；`optX` 退化为默认值，最坏只是少一条数据。
 * 这与 `JwParser` 的既有约定一致（那个文件记录了 `JsonNull.content` 返回字面量
 * `"null"` 的坑，这里同样适用）。
 */
object SmartClassParser {

    /** 一栋教学楼。 */
    data class Building(val id: String, val name: String)

    /** 一种节次划分（默认节次=6 大节、小节次=12 小节）。 */
    data class NodeType(val id: String, val name: String)

    /** 某个时段内的空教室。 */
    data class FreeRoom(
        val classroomId: Long,
        val name: String,
        /** 空座率 0.0~1.0；服务端可能返回 null（实测存在），表示暂无数据。 */
        val noSeatRate: Double?,
        /** 座位数；0 或缺失表示未知。 */
        val seatCount: Int,
    )

    /**
     * 一个时段及其空教室。
     *
     * @param nodeId 时段 ID（可用于按单个时段过滤查询）
     * @param nodeName 时段名（"第一大节"）
     * @param startTime 原始时间串 `yyyy-MM-dd HH:mm:ss`；服务端固定返回 2000-01-01 占位日期
     * @param endTime 同上
     */
    data class RoomSlot(
        val nodeId: String,
        val nodeName: String,
        val startTime: String,
        val endTime: String,
        val rooms: List<FreeRoom>,
    ) {
        /** 起始时刻的 `HH:mm`（解析失败返回空串）。 */
        val startHm: String get() = hm(startTime)

        /** 结束时刻的 `HH:mm`。 */
        val endHm: String get() = hm(endTime)

        private fun hm(raw: String): String =
            if (raw.length >= 16) raw.substring(11, 16) else ""
    }

    /** `data` 数组是否为列表响应且 code==0。 */
    private fun dataArray(body: String): List<JSONObject>? = runCatching {
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 0) return null
        val arr = root.optJSONArray("data") ?: return null
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }.getOrNull()

    /**
     * 服务端返回的错误消息（成功时返回 null）。
     *
     * **无法解析为 JSON 时返回"响应格式异常"而不是 null**：null 的语义是"这是一个
     * code==0 的成功响应"，把解析失败也归到 null 会让网关维护页、WAF 挑战页、
     * 校园网认证门户的 HTML 被当成"成功但没有数据"，最终在界面上显示
     * "当前没有查询到无课教室"——一个貌似正常但错误的结论。调用方（`SmartClassApi.fetch`）
     * 依赖"非 null 即失败"这一约定。
     */
    fun errorMessage(body: String): String? = runCatching {
        val root = JSONObject(body)
        if (root.optInt("code", -1) == 0) null else root.optString("msg").ifBlank { "未知错误" }
    }.getOrElse { "响应格式异常，请稍后重试" }

    /** 解析教学楼列表。 */
    fun parseBuildings(body: String): List<Building> {
        val data = dataArray(body).orEmpty()
        val list = data.mapNotNull { o ->
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() || name.isBlank()) null else Building(id, name)
        }
        // 元素级一致性校验：data 里有条目却一条都没解析出来 → 服务端字段形态变了。
        // 返回空列表会被界面翻译成"没有获取到教学楼列表"（尚可）或"没有空教室"（误导），
        // 抛错才能走明确的失败路径。见 parseRoomSlots 的同款注释。
        if (data.isNotEmpty() && list.isEmpty()) {
            throw SmartClassException("教学楼列表格式异常（服务端字段可能已调整）")
        }
        return list
    }

    /** 解析节次类型列表。 */
    fun parseNodeTypes(body: String): List<NodeType> =
        dataArray(body).orEmpty().mapNotNull { o ->
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank()) null else NodeType(id, name.ifBlank { "默认节次" })
        }

    /**
     * 解析空教室时段列表。
     *
     * 跳过没有教室的时段：实测某些时段 `classroomItems` 为空数组，
     * 展示一个空标题只会让页面变长而没有信息量。
     */
    fun parseRoomSlots(body: String): List<RoomSlot> {
        val data = dataArray(body).orEmpty()
        val slots = data.mapNotNull { o ->
            val nodeId = o.optString("nodeId")
            val nodeName = o.optString("nodeName")
            if (nodeName.isBlank()) return@mapNotNull null
            val items = o.optJSONArray("classroomItems") ?: return@mapNotNull null
            val rooms = (0 until items.length()).mapNotNull { i ->
                val c = items.optJSONObject(i) ?: return@mapNotNull null
                val name = c.optString("classroomName")
                if (name.isBlank()) return@mapNotNull null
                // noSeatRate 为 JSON null 时 optDouble 会返回默认值，
                // 所以必须先判 isNull 才能区分"没有数据"与"空座率真的是 0"
                val rate = if (c.isNull("noSeatRate")) null else c.optDouble("noSeatRate").takeIf { it.isFinite() }
                FreeRoom(
                    classroomId = c.optLong("classroomId", 0L),
                    name = name,
                    noSeatRate = rate?.coerceIn(0.0, 1.0),
                    seatCount = c.optInt("seatCount", 0),
                )
            }
            if (rooms.isEmpty()) null else RoomSlot(nodeId, nodeName, o.optString("startTime"), o.optString("endTime"), rooms)
        }
        // 元素级一致性校验：HTTP 状态与顶层 JSON 已由调用方把关（M1 修复），
        // 但字段改名/变类型会让 data 里的条目被逐条丢弃 → 返回空列表 →
        // 界面显示"今天没有查询到无课教室"——把"服务端/我方解析出问题"说成了真结论。
        // data 有内容却解析不出任何时段时，明确报错。
        if (data.isNotEmpty() && slots.isEmpty()) {
            throw SmartClassException("空教室数据格式异常（服务端字段可能已调整）")
        }
        return slots
    }
}
