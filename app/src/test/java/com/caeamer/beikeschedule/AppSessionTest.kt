package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.pref.AppSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 前台会话序号：起始 0，App 每次退到后台 +1（课表据此重新定位到当前周）。
 *
 * 它是进程级单例，同一个 JVM 里跑全部单测，所以断言一律写"相对增量"，
 * 不依赖测试方法的执行顺序。
 */
class AppSessionTest {

    @Test
    fun `退到后台一次 序号加一`() {
        val before = AppSession.epoch.value
        AppSession.markBackgrounded()
        assertEquals(before + 1, AppSession.epoch.value)
    }

    @Test
    fun `反复退到后台 序号累加`() {
        val before = AppSession.epoch.value
        repeat(3) { AppSession.markBackgrounded() }
        assertEquals(before + 3, AppSession.epoch.value)
    }
}
