package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.remote.SmartClassApi
import com.caeamer.beikeschedule.data.remote.SmartClassException
import com.caeamer.beikeschedule.data.remote.SmartClassKeyProvider
import com.caeamer.beikeschedule.data.remote.SmartClassParser
import com.caeamer.beikeschedule.model.RoomNameOrder

/**
 * 无课教室的数据入口。
 *
 * 职责：串起"取 key → 签名 → 调接口 → 排序"这一串，并处理 key 被服务端轮换的自愈。
 *
 * ## 关于"签名被拒"的自愈
 *
 * `csrkKey` 由服务端下发且可轮换。轮换后请求会返回 `csrf key validate error`。
 * 此时唯一正确的做法是**丢弃缓存的 key 并重取一次**——这对用户完全透明，
 * 而不是弹一个"签名错误"让用户莫名其妙。所以这里每个接口都做一次重试。
 */
class FreeRoomRepository(
    private val keyProvider: SmartClassKeyProvider,
    private val api: SmartClassApi = SmartClassApi(),
) {

    /**
     * 首次进入 / 下拉刷新时调用：取楼栋列表与节次类型，并顺带校正服务器时钟
     * （后续每次签名都要用）。
     */
    suspend fun loadMeta(): FreeRoomResult {
        keyProvider.syncClockOrSkip()
        val signedReq = signed()
        val buildings = api.listBuildings(signedReq).getOrElse { throw it }
        // 节次类型失败不致命：没有它也能展示楼栋，只是点进去查不到（会在查询时报错）
        val nodeTypes = api.listNodeTypes(signedReq).getOrDefault(emptyList())
        cycleTypeId = pickCycleType(nodeTypes)
        return FreeRoomResult(buildings, nodeTypes)
    }

    /** 一次查询的结果。 */
    data class FreeRoomResult(
        val buildings: List<SmartClassParser.Building>,
        val nodeTypes: List<SmartClassParser.NodeType>,
    )

    /**
     * 默认节次类型 ID（=6 大节，与课表节次对齐）。
     * [loadMeta] 时解析并缓存，避免每次查询都多打一次 listNodeTypes。
     */
    @Volatile
    private var cycleTypeId: String? = null

    /**
     * 查询某栋楼的空教室。
     *
     * @param buildingId 楼栋 ID
     */
    suspend fun loadFreeRooms(buildingId: String): List<SmartClassParser.RoomSlot> {
        // 缓存未命中（未调 loadMeta 或那次失败）时才补一次
        val cycleId = cycleTypeId ?: withSignedRetry { signed -> api.listNodeTypes(signed) }
            .let { pickCycleType(it) }
            ?.also { cycleTypeId = it }
            ?: return emptyList()
        val slots = withSignedRetry { signed ->
            api.freeClassRooms(signed, buildingId = buildingId, cycleTypeId = cycleId)
        }
        return sortRooms(slots)
    }

    /**
     * 选默认节次类型：优先名字为"默认节次"的（=6 大节，与课表节次对齐），
     * 否则取第一个。实测该平台恰好有"默认节次"与"小节次"两种，我们要前者。
     */
    internal fun pickCycleType(types: List<SmartClassParser.NodeType>): String? =
        (types.firstOrNull { it.name.contains("默认") } ?: types.firstOrNull())?.id

    /** 每个时段内的教室按名字自然排序（503 在 507 前、1002 在 503 后）。 */
    internal fun sortRooms(slots: List<SmartClassParser.RoomSlot>): List<SmartClassParser.RoomSlot> =
        slots.map { slot -> slot.copy(rooms = slot.rooms.sortedWith(compareBy(RoomNameOrder) { it.name })) }

    /**
     * 取 key → 签名 → 调用；遇到"签名被拒"就丢弃缓存重试一次。
     *
     * 只重试一次：重试仍失败说明不是 key 过期（可能是服务端故障），
     * 继续重试只会拖慢用户的等待。
     */
    private suspend fun <T> withSignedRetry(call: suspend (SmartClassApi.SignedRequest) -> Result<T>): T {
        val first = call(signed())
        val ex = first.exceptionOrNull()
        if (ex is SmartClassException && ex.tokenRejected) {
            keyProvider.invalidate()
            val second = call(signed(forceRefresh = true))
            return second.getOrElse { throw it }
        }
        return first.getOrElse { throw it }
    }

    private suspend fun signed(forceRefresh: Boolean = false) = SmartClassApi.SignedRequest(
        csrkKey = keyProvider.csrkKey(forceRefresh),
        timeMillis = keyProvider.signingTimeMillis(),
    )
}

/**
 * 校正服务器时钟；失败不抛异常。
 *
 * 时间校正失败**不应该**让功能不可用：多数设备时钟是准的，用本机时间签名的成功率
 * 依然很高；把它做成硬失败反而会让"网络抖动"变成"整个页面打不开"。
 */
private suspend fun SmartClassKeyProvider.syncClockOrSkip() {
    runCatching { syncClock() }
}
