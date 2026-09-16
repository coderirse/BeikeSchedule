package com.caeamer.beikeschedule.ui.freeroom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.remote.SmartClassApi
import com.caeamer.beikeschedule.data.remote.SmartClassException
import com.caeamer.beikeschedule.data.remote.SmartClassKeyProvider
import com.caeamer.beikeschedule.data.remote.SmartClassParser
import com.caeamer.beikeschedule.data.repo.FreeRoomRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime

/** 无课教室页面状态。 */
data class FreeRoomUiState(
    val buildings: List<SmartClassParser.Building> = emptyList(),
    val selectedBuildingId: String = "",
    val slots: List<SmartClassParser.RoomSlot> = emptyList(),
    /** 首次加载（尚无任何数据可显示）。 */
    val loading: Boolean = false,
    /** 下拉刷新中（已有旧数据，仍在刷新）。 */
    val refreshing: Boolean = false,
    val error: String? = null,
    /**
     * 当前展开的时段下标。
     *
     * `-1` = 全部收起；`null` = 跟随当前时间（用户没手动操作过）。
     * 每次刷新会重置为 null，因为"现在能去哪"才是打开这个页面的意图。
     */
    val expandedIndex: Int? = null,
) {
    /** 是否已有可展示的数据（用于区分"首屏加载"与"刷新失败"）。 */
    val hasData: Boolean get() = slots.isNotEmpty()

    /**
     * 实际展开的下标：用户手动选过就用他的，否则用当前时间所在的大节
     * （不在任何时段内则展开第一个）。
     */
    val effectiveExpandedIndex: Int
        get() = expandedIndex ?: currentSlotIndex().takeIf { it >= 0 } ?: 0

    /**
     * 当前时间落在第几个大节（0 起）；不在任何时段内返回 -1。
     *
     * 时段是 `HH:mm` 文本。解析失败时**跳过该段**而不是让整个判断失效——
     * 一个时段的时间串异常不该导致"默认展开"功能整体失灵。
     */
    fun currentSlotIndex(now: LocalTime = LocalTime.now()): Int {
        slots.forEachIndexed { i, slot ->
            val start = runCatching { LocalTime.parse(slot.startHm) }.getOrNull() ?: return@forEachIndexed
            val end = runCatching { LocalTime.parse(slot.endHm) }.getOrNull() ?: return@forEachIndexed
            if (!now.isBefore(start) && !now.isAfter(end)) return i
        }
        return -1
    }
}

/**
 * 无课教室页面的 ViewModel。
 *
 * 数据来源是校外平台（`ustb.smartclass.cn`），与教务系统相互独立、也无需登录。
 * 打开即查一次 + 下拉刷新；空教室是实时数据，不做跨会话缓存。
 */
class FreeRoomViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val repo = FreeRoomRepository(SmartClassKeyProvider(app, SmartClassApi()))

    private val _state = MutableStateFlow(FreeRoomUiState())
    val state: StateFlow<FreeRoomUiState> = _state.asStateFlow()

    init {
        load(initial = true)
    }

    /**
     * 展开/收起某个时段。
     *
     * 用显式的 `-1` 表示"全部收起"（而非把 expandedIndex 置回 null）：
     * null 的语义是"跟随当前时间"，若复用 null 会导致收起后又自动展开。
     */
    fun toggleSlot(index: Int) {
        val cur = _state.value
        val next = if (cur.effectiveExpandedIndex == index && cur.expandedIndex != -1) -1 else index
        _state.value = cur.copy(expandedIndex = next)
    }

    fun selectBuilding(buildingId: String) {
        if (buildingId == _state.value.selectedBuildingId) return
        _state.value = _state.value.copy(selectedBuildingId = buildingId, slots = emptyList(), error = null)
        viewModelScope.launch {
            settings.setFreeRoomBuilding(buildingId)
            loadRooms(buildingId)
        }
    }

    fun refresh() = load(initial = false)

    /** 错误态下的重试。 */
    fun retry() = load(initial = _state.value.buildings.isEmpty())

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = initial,
                refreshing = !initial,
                error = null,
                // 刷新后回到"跟随当前时间"
                expandedIndex = null,
            )
            try {
                val meta = repo.loadMeta()
                if (meta.buildings.isEmpty()) {
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        error = "没有获取到教学楼列表，请稍后重试",
                    )
                    return@launch
                }
                // 优先用上次选的楼；若它已不存在（服务端调整过楼栋）则退回第一栋
                val remembered = settings.freeRoomBuilding.first()
                val selected = meta.buildings.firstOrNull { it.id == remembered }?.id
                    ?: meta.buildings.first().id
                _state.value = _state.value.copy(
                    buildings = meta.buildings,
                    selectedBuildingId = selected,
                    loading = false,
                )
                loadRooms(selected)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, refreshing = false, error = friendly(e))
            }
        }
    }

    private fun loadRooms(buildingId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = _state.value.buildings.isNotEmpty(), error = null)
            try {
                val slots = repo.loadFreeRooms(buildingId)
                _state.value = _state.value.copy(slots = slots, loading = false, refreshing = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, refreshing = false, error = friendly(e))
            }
        }
    }

    /**
     * 把异常翻成人话。
     *
     * "签名被拒"在 Repository 层已自动重试过一次，走到这里说明重试也没成功——
     * 最可能是 App 内置密钥已过期（服务端轮换了 key，且当前网络取不到新配置）。
     * 这时要说清"不是你的问题"，否则用户会反复下拉刷新。
     */
    private fun friendly(e: Exception): String = when {
        e is SmartClassException && e.tokenRejected ->
            "服务暂时不可用：签名密钥可能已更新，请稍后重试或检查 App 更新"
        e.message.isNullOrBlank() -> "加载失败，请检查网络后重试"
        else -> e.message!!.takeIf { it.isNotBlank() } ?: "加载失败，请检查网络后重试"
    }
}
