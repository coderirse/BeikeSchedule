package com.caeamer.beikeschedule.ui.todo

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * iOS 风格纵向滚轮单列：拖动滚动 + 边缘吸附 + 点选居中。
 *
 * 视口正中的项即"当前选中项"：放大提亮并显示单位后缀；上下项随距离增大渐隐渐小，
 * 滚动过程中按**分数距离**连续过渡（非离散三档），观感与系统滚轮一致。
 *
 * 初始位置直接交给 LazyListState，不在显示 0 后再异步定位，避免默认项覆盖父级值。
 * 回调仅在停止滚动且布局有效时发出，同时观察中心项，不能仅观察 isScrollInProgress：
 * 程序化定位的 true/false 可能合并，导致画面已换项而父级仍保留旧值。
 * 确定按钮应直接读取同一个 state 的 centeredItemIndex，快速确认时也与高亮一致。
 * 禁止根据回传的 initialIndex 重定位，否则会中断惯性滚动。
 *
 * @param items   各项文本（如 "00".."23"，调用方负责格式化）
 * @param suffix  选中项显示的单位后缀（如 "时"；null 则不显示）
 * @param initialIndex 初始选中下标（打开时定位到该项）
 * @param onCenterChange 视口中心项变化时回调（**滚动停止后**才回调）
 */
@Composable
fun WheelColumn(
    items: List<String>,
    initialIndex: Int,
    onCenterChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    suffix: String? = null,
    itemHeight: Dp = 44.dp,
    visibleCount: Int = 5,
    state: LazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
    ),
) {
    require(visibleCount % 2 == 1 && visibleCount > 0) { "visibleCount 必须为正奇数，保证存在正中槽位" }
    require(items.isNotEmpty()) { "items 不能为空（没有可滚动的槽位）" }
    val scope = rememberCoroutineScope()
    val currentOnCenterChange by rememberUpdatedState(onCenterChange)
    val clampedInitial = initialIndex.coerceIn(0, items.lastIndex)

    // 上下各留半个视口的内容边距：首尾项也能滚到正中。
    // 在此布局下 scrollToItem(i) 恰好把第 i 项放进中心槽位。
    val pad = itemHeight * (visibleCount / 2)

    // 视口中心项 = 当前选中项
    val centerIndex by remember(state) {
        derivedStateOf {
            state.centeredItemIndex(clampedInitial)
        }
    }
    // 滚动停止（含拖动与惯性都结束）时才把最终选中项回写父级
    LaunchedEffect(state) {
        snapshotFlow {
            if (state.isScrollInProgress || state.layoutInfo.visibleItemsInfo.isEmpty()) null else centerIndex
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { currentOnCenterChange(it) }
    }

    LazyColumn(
        state = state,
        modifier = modifier.height(itemHeight * visibleCount),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = pad),
    ) {
        items(items.size) { index ->
            WheelItem(
                text = items[index],
                suffix = suffix,
                index = index,
                listState = state,
                itemHeight = itemHeight,
                onSelect = { scope.launch { state.animateScrollToItem(index) } },
            )
        }
    }
}

/** 高亮和确认共用视口中心算法；尚未布局时保持原值，不把未测量状态误当作 0。 */
internal fun LazyListState.centeredItemIndex(fallback: Int): Int {
    val info = layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index ?: fallback
}

/** 滚轮的单个项：按自身与视口中心的分数距离插值缩放与透明度。 */
@Composable
private fun WheelItem(
    text: String,
    suffix: String?,
    index: Int,
    listState: LazyListState,
    itemHeight: Dp,
    onSelect: () -> Unit,
) {
    // 与视口中心的距离（单位：项高的分数）。项不在视口内视为极远。
    val distance by remember(index) {
        derivedStateOf {
            val info = listState.layoutInfo
            val vc = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                ?: return@derivedStateOf Float.MAX_VALUE
            abs(item.offset + item.size / 2f - vc) / item.size
        }
    }
    // t: 1=正中，0=距中心 2 项及以上
    val t = (1f - distance / 2f).coerceIn(0f, 1f)
    val isSelected = distance < 0.5f

    Box(
        Modifier
            .fillMaxWidth()
            .height(itemHeight)
            // selectable 而非 clickable：TalkBack 读得出"已选中/未选中"
            .selectable(selected = isSelected, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text,
                fontSize = (18 + 10 * t).sp,
                fontWeight = if (t > 0.8f) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(0.15f + 0.85f * t),
            )
            if (suffix != null) {
                Text(
                    suffix,
                    style = MaterialTheme.typography.titleMedium,
                    // 常驻占位、未选中时透明：后缀随选中出现/消失会让数字水平位移（滚动时抖动）
                    modifier = Modifier
                        .alpha(if (isSelected) t else 0f)
                        .padding(bottom = 3.dp), // 后缀贴住数字基线
                )
            }
        }
    }
}
