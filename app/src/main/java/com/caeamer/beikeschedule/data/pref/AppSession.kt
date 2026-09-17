package com.caeamer.beikeschedule.data.pref

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 前台会话序号（**不落盘**）。
 *
 * 语义：App 每次退到后台（Home / 切到其他应用 / 锁屏 / 划掉后台）序号 +1，
 * 下一次进入前台即为一个新的会话。课表据此把"用户选中的周"打回未选，重新定位到当前周；
 * 而 App 内切换 Tab、旋转屏幕、深浅色切换都不 +1，用户手动翻到的周次得以保留。
 *
 * 与 [ScorePrivacy] 同构：都是会话级状态，**绝不能持久化**——持久化会让"上次看的周"
 * 跨进程存活，与"退出即回到本周"的诉求相悖。
 *
 * 用自增序号而不是布尔开关：布尔需要"谁去复位"的约定，漏复位就只生效一次；
 * 序号自增后由订阅方（ScheduleViewModel）自己 diff，天然幂等。
 */
object AppSession {

    private val _epoch = MutableStateFlow(0)

    /** 会话序号；每次退到后台后 +1。划掉后台会起新进程，从 0 重新计数。 */
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    /** App 退到后台时调用（MainActivity.onStop，配置变更除外）。 */
    fun markBackgrounded() {
        _epoch.value += 1
    }
}
