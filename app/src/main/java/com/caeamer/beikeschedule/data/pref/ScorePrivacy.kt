package com.caeamer.beikeschedule.data.pref

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 成绩隐私开关的会话级状态（不落盘）。
 *
 * 语义：默认隐藏；点小眼睛显示后，只要 App 停留在前台（含切换课表/教务/我的 Tab）就一直显示；
 * App 退到后台（Home/切其他应用/锁屏/划掉后台）立即复位为隐藏，下次回到前台仍是隐藏。
 * 因此不能持久化到 DataStore——持久化会让"显示"状态跨进程存活，违背退出即隐藏的要求。
 */
object ScorePrivacy {

    private val _hidden = MutableStateFlow(true)
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    fun toggle() {
        _hidden.value = !_hidden.value
    }

    /** 复位为隐藏（App 退到后台时调用）。 */
    fun hide() {
        _hidden.value = true
    }
}
