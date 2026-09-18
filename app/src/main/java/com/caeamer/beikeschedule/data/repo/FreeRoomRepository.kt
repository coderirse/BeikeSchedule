package com.caeamer.beikeschedule.data.repo

import android.util.Log
import com.caeamer.beikeschedule.data.remote.SignedRequest
import com.caeamer.beikeschedule.data.remote.SmartClassApi
import com.caeamer.beikeschedule.data.remote.SmartClassDataSource
import com.caeamer.beikeschedule.data.remote.SmartClassException
import com.caeamer.beikeschedule.data.remote.SmartClassKeyProvider
import com.caeamer.beikeschedule.data.remote.SmartClassKeySource
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
 * 而不是弹一个"签名错误"让用户莫名其妙。
 *
 * **每个接口都必须走 [withSignedRetry]**：曾经只有 [loadFreeRooms] 走了，
 * 而 [loadMeta] 的 `listBuildings` 是整条链路的第一个请求，轮换后失败在这里 ——
 * 自愈代码因此永远不可达，用户只能清应用数据恢复。见 `FreeRoomRepositoryTest`。
 */
class FreeRoomRepository(
    private val keyProvider: SmartClassKeySource,
    private val api: SmartClassDataSource = SmartClassApi(),
) {

    /**
     * 首次进入 / 下拉刷新时调用：取楼栋列表与节次类型，并顺带校正服务器时钟
     * （后续每次签名都要用）。
     */
    suspend fun loadMeta(): FreeRoomResult {
        // 校时失败不是硬依赖：多数设备时钟是准的，用本机时间签名成功率依然很高。
        // 取消异常必须放行：runCatching 会把它当普通失败吞掉，破坏结构化取消。
        try {
            keyProvider.syncClock()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        val buildings = withSignedRetry { signed -> api.listBuildings(signed) }
        // 节次类型失败不致命：没有它也能展示楼栋，只是点进去查不到（会在查询时报错）
        val nodeTypes = try {
            withSignedRetry { signed -> api.listNodeTypes(signed) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
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
     * @throws SmartClassException 取不到节次类型，或接口失败（含 HTTP 状态异常）
     */
    suspend fun loadFreeRooms(buildingId: String): List<SmartClassParser.RoomSlot> {
        // 缓存未命中（未调 loadMeta 或那次失败）时才补一次；取消异常放行（理由同 loadMeta）
        val cycleId = cycleTypeId
            ?: run {
                val types = try {
                    withSignedRetry { signed -> api.listNodeTypes(signed) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                pickCycleType(types)
            }?.also { cycleTypeId = it }
            // 取不到节次类型必须报错：静默返回空列表会被界面表达成"没有空教室"
            ?: throw SmartClassException("没有获取到节次类型，请稍后重试")
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
    private suspend fun <T> withSignedRetry(call: suspend (SignedRequest) -> Result<T>): T {
        val first = call(signed())
        val ex = first.exceptionOrNull()
        if (ex is SmartClassException && ex.tokenRejected) {
            // 留一条日志：这条路径代表"服务端轮换了 csrkKey 且本地缓存已过期"，
            // 排查线上问题时是最关键的线索（用户侧只会看到一句"暂时查不到空教室"）
            Log.w(TAG, "签名被拒，丢弃缓存的 csrkKey 后重试一次", ex)
            keyProvider.invalidate()
            val second = call(signed(forceRefresh = true))
            return second.getOrElse { throw it }
        }
        return first.getOrElse { throw it }
    }

    private suspend fun signed(forceRefresh: Boolean = false) = SignedRequest(
        csrkKey = keyProvider.csrkKey(forceRefresh),
        timeMillis = keyProvider.signingTimeMillis(),
    )

    private companion object {
        const val TAG = "FreeRoomRepository"
    }
}
