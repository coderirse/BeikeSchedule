package com.caeamer.beikeschedule.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * 随时间推进的"现在"：只在 RESUMED 时走时钟，且唤醒点对齐到下一个整分钟。
 *
 * 两点都是踩过的坑：
 * - 组合期直接读 `LocalDateTime.now()` 不会随午夜/下课时刻刷新（页面在后台挂一夜，
 *   回来还是昨天的"今天"、"下一节课"图钉也不会前移）；
 * - 裸 `flow { while (true) { emit(now); delay(60s) } }.collectAsState()` 在 App 退到后台后
 *   仍在空转，每分钟让整屏重组一次，而且从进入页面起算的节拍会让跨点时刻最多滞后 60 秒。
 *
 * 列表页/考试倒计时/日程分组这类"用当前时间做判断"的场景统一用它。
 *
 * @param tickMillis 刷新节拍：默认对齐整分钟；需要更细粒度（如无课教室的
 *   "进行中/已结束" 半分钟判定）传更小的值，仍只在 RESUMED 走时钟。
 */
@Composable
fun rememberNow(tickMillis: Long = 60_000L): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, tickMillis) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val current = LocalDateTime.now()
                now = current
                if (tickMillis >= 60_000L) {
                    // 对齐到下一个整分钟：跨过整分钟（上课/下课/午夜）的时刻不再滞后
                    delay(60_000L - (current.second * 1_000L + current.nano / 1_000_000L))
                } else {
                    delay(tickMillis)
                }
            }
        }
    }
    return now
}
