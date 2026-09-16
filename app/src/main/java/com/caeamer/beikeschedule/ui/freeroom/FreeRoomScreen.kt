package com.caeamer.beikeschedule.ui.freeroom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.remote.SmartClassParser
import kotlin.math.roundToInt

/**
 * 无课教室页面（贝壳教学平台）。
 *
 * 数据来自校外平台，与教务系统独立、无需登录。打开即查一次、支持下拉刷新，
 * 不做自动轮询 —— 空座率虽会变，但用户看一眼就走的场景不需要实时推送。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeRoomScreen(viewModel: FreeRoomViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.buildings.isNotEmpty()) {
                BuildingTabs(
                    buildings = state.buildings,
                    selectedId = state.selectedBuildingId,
                    onSelect = viewModel::selectBuilding,
                )
            }

            when {
                state.loading -> CenterBox { CircularProgressIndicator() }

                // 有旧数据时不整屏报错：顶部给一行提示，下面继续显示上次的结果，
                // 比"刷新失败就白屏"更符合用户预期（尤其地铁里网络抖动）
                state.error != null && !state.hasData -> ErrorBox(state.error!!, viewModel::retry)

                !state.hasData -> CenterBox {
                    Text(
                        "当前没有查询到无课教室",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    state.error?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    SlotList(state = state, onToggle = viewModel::toggleSlot)
                }
            }
        }
    }
}

/** 楼栋横向切换（可滚动，5 栋楼在窄屏上放不下）。 */
@Composable
private fun BuildingTabs(
    buildings: List<SmartClassParser.Building>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buildings.forEach { b ->
            val selected = b.id == selectedId
            Text(
                b.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(b.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** 6 个大节，每个可展开/收起；当前时段默认展开。 */
@Composable
private fun SlotList(state: FreeRoomUiState, onToggle: (Int) -> Unit) {
    val expanded = state.effectiveExpandedIndex
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.slots, key = { it.nodeId.ifBlank { it.nodeName } }) { slot ->
            SlotCard(
                slot = slot,
                expanded = state.slots.indexOf(slot) == expanded,
                onToggle = { onToggle(state.slots.indexOf(slot)) },
            )
        }
    }
}

@Composable
private fun SlotCard(slot: SmartClassParser.RoomSlot, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(slot.nodeName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val span = if (slot.startHm.isNotEmpty() && slot.endHm.isNotEmpty()) {
                    "${slot.startHm}-${slot.endHm}"
                } else ""
                Text(
                    listOf(span, "${slot.rooms.size} 间空教室").filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                slot.rooms.forEach { RoomRow(it) }
            }
        }
    }
}

/** 单间教室：教室名 + 座位数 + 空座率。 */
@Composable
private fun RoomRow(room: SmartClassParser.FreeRoom) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(room.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (room.seatCount > 0) {
            Text(
                "${room.seatCount} 座",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
        }
        // noSeatRate 为 null 表示"暂无数据"，必须与"空座率真的是 0%"区分开
        val rateText = room.noSeatRate?.let { "${(it * 100).roundToInt()}% 空座" } ?: "--"
        Text(
            rateText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (room.noSeatRate != null && room.noSeatRate >= 0.5) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("重试")
        }
    }
}
