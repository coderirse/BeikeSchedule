package com.caeamer.beikeschedule.data.repo

import com.caeamer.beikeschedule.data.remote.SignedRequest
import com.caeamer.beikeschedule.data.remote.SmartClassDataSource
import com.caeamer.beikeschedule.data.remote.SmartClassException
import com.caeamer.beikeschedule.data.remote.SmartClassKeySource
import com.caeamer.beikeschedule.data.remote.SmartClassParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无课教室 Repository 的自愈路径与失败语义单测。
 *
 * 这里锁定的是本项目真实踩过的两个坑：
 * 1. `loadMeta` 的**第一个请求**曾经没走重试，导致服务端轮换 `csrkKey` 后
 *    自愈代码整条不可达、功能对老用户永久失效（只覆盖了一半接口）；
 * 2. "取不到节次类型"曾经被静默表达成"没有空教室"。
 */
class FreeRoomRepositoryTest {

    private class FakeKeys(
        private val failSyncClock: Boolean = false,
    ) : SmartClassKeySource {
        var invalidateCount = 0
        var forceRefreshCount = 0
        var syncClockCount = 0

        override suspend fun csrkKey(forceRefresh: Boolean): String {
            if (forceRefresh) forceRefreshCount++
            return "s0k6e5a1t3i46kglaz9"
        }

        override fun signingTimeMillis(): Long = 1_789_564_775_269L

        override suspend fun syncClock() {
            syncClockCount++
            if (failSyncClock) throw IllegalStateException("boom")
        }

        override suspend fun invalidate() {
            invalidateCount++
        }
    }

    /** 可编程的数据源：按调用次序返回"签名被拒"或成功。 */
    private class FakeApi(
        private val buildingRejections: Int = 0,
        private val roomRejections: Int = 0,
        private val nodeTypes: List<SmartClassParser.NodeType> = listOf(SmartClassParser.NodeType("n1", "默认节次")),
        private val rooms: List<SmartClassParser.RoomSlot> = listOf(
            SmartClassParser.RoomSlot(
                nodeId = "n1",
                nodeName = "第一大节",
                startTime = "2000-01-01 07:58:00",
                endTime = "2000-01-01 09:35:00",
                rooms = listOf(
                    SmartClassParser.FreeRoom(1L, "教学楼1002", 1.0, 40),
                    SmartClassParser.FreeRoom(2L, "教学楼503", 0.9, 96),
                ),
            ),
        ),
    ) : SmartClassDataSource {
        var buildingCalls = 0
        var roomCalls = 0
        var nodeTypeCalls = 0

        override suspend fun fetchDomainConfig(): String? = null
        override suspend fun serverDateMillis(): Long? = null
        override suspend fun serverTimeMillis(signed: SignedRequest): Long? = null

        override suspend fun listBuildings(signed: SignedRequest): Result<List<SmartClassParser.Building>> {
            buildingCalls++
            if (buildingCalls <= buildingRejections) return rejected()
            return Result.success(listOf(SmartClassParser.Building("b1", "逸夫楼")))
        }

        override suspend fun listNodeTypes(signed: SignedRequest): Result<List<SmartClassParser.NodeType>> {
            nodeTypeCalls++
            return Result.success(nodeTypes)
        }

        override suspend fun freeClassRooms(
            signed: SignedRequest,
            buildingId: String,
            cycleTypeId: String,
            nodeId: String,
        ): Result<List<SmartClassParser.RoomSlot>> {
            roomCalls++
            if (roomCalls <= roomRejections) return rejected()
            return Result.success(rooms)
        }

        private fun rejected(): Result<Nothing> = Result.failure(
            SmartClassException("csrf key validate error", tokenRejected = true),
        )
    }

    @Test
    fun `loadMeta - 楼栋请求被拒时丢弃缓存并重试一次`() = runBlocking {
        // 这是本类存在的理由：loadMeta 是整条链路的第一个请求，
        // 服务端轮换 csrkKey 后失败在这里；若不重试，自愈代码永远跑不到。
        val keys = FakeKeys()
        val api = FakeApi(buildingRejections = 1)
        val meta = FreeRoomRepository(keys, api).loadMeta()

        assertEquals(1, keys.invalidateCount)
        assertEquals(1, keys.forceRefreshCount)
        assertEquals(2, api.buildingCalls)
        assertEquals(listOf("逸夫楼"), meta.buildings.map { it.name })
    }

    @Test
    fun `tokenRejected 重试前会重新校正服务器时钟`() = runBlocking {
        // 时钟偏慢同样表现为 token 被拒；只重取 key 救不了时间问题
        val keys = FakeKeys()
        val api = FakeApi(buildingRejections = 1)
        FreeRoomRepository(keys, api).loadMeta()

        // loadMeta 开头 1 次 + 被拒重试前 1 次
        assertEquals(2, keys.syncClockCount)
    }

    @Test
    fun `loadFreeRooms - 空教室请求被拒时同样自愈`() = runBlocking {
        val keys = FakeKeys()
        val api = FakeApi(roomRejections = 1)
        val repo = FreeRoomRepository(keys, api)

        val slots = repo.loadFreeRooms("b1")

        assertEquals(1, keys.invalidateCount)
        assertEquals(2, api.roomCalls)
        assertEquals(1, slots.size)
    }

    @Test
    fun `重试后仍失败则抛出 - 不做无限重试`() = runBlocking {
        val keys = FakeKeys()
        val api = FakeApi(buildingRejections = 2)
        var thrown: Exception? = null
        try {
            FreeRoomRepository(keys, api).loadMeta()
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("必须抛出异常", thrown is SmartClassException)
        assertEquals(2, api.buildingCalls)   // 只重试一次
    }

    @Test
    fun `非签名类错误不重试 - 不丢弃可用的 key 缓存`() = runBlocking {
        val keys = FakeKeys()
        val api = object : SmartClassDataSource by FakeApi() {
            override suspend fun listBuildings(signed: SignedRequest) =
                Result.failure<List<SmartClassParser.Building>>(SmartClassException("服务端异常"))
        }
        var thrown: Exception? = null
        try {
            FreeRoomRepository(keys, api).loadMeta()
        } catch (e: Exception) {
            thrown = e
        }
        assertEquals("服务端异常", thrown?.message)
        assertEquals(0, keys.invalidateCount)
        assertEquals(0, keys.forceRefreshCount)
    }

    @Test
    fun `节次类型缺失时报错 - 不能静默当成没有空教室`() = runBlocking {
        val keys = FakeKeys()
        val api = FakeApi(nodeTypes = emptyList())
        val repo = FreeRoomRepository(keys, api)
        repo.loadMeta()

        var thrown: Exception? = null
        try {
            repo.loadFreeRooms("b1")
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("必须抛出异常而不是返回空列表", thrown is SmartClassException)
        assertEquals(0, api.roomCalls)
    }

    @Test
    fun `教室名按自然序排列 - 位数不同也比数值`() = runBlocking {
        val slots = FreeRoomRepository(FakeKeys(), FakeApi()).loadFreeRooms("b1")
        assertEquals(listOf("教学楼503", "教学楼1002"), slots.single().rooms.map { it.name })
    }

    @Test
    fun `默认节次类型优先选名字含默认的`() {
        val repo = FreeRoomRepository(FakeKeys(), FakeApi())
        val picked = repo.pickCycleType(
            listOf(
                SmartClassParser.NodeType("small", "小节次"),
                SmartClassParser.NodeType("normal", "默认节次"),
            ),
        )
        assertEquals("normal", picked)
        // 服务端改名/只给一种时退回第一个，而不是抛异常
        assertEquals("small", repo.pickCycleType(listOf(SmartClassParser.NodeType("small", "小节次"))))
        assertEquals(null, repo.pickCycleType(emptyList()))
    }

    @Test
    fun `校时失败不影响取数据 - 时钟校正不是硬依赖`() = runBlocking {
        val keys = FakeKeys(failSyncClock = true)
        val meta = FreeRoomRepository(keys, FakeApi()).loadMeta()
        assertEquals(1, keys.syncClockCount)
        assertEquals(1, meta.buildings.size)
    }
}
