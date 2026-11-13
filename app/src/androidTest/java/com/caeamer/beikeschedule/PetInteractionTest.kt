package com.caeamer.beikeschedule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.ui.pet.PetPreferencesStore
import com.caeamer.beikeschedule.ui.pet.PetFrequency
import com.caeamer.beikeschedule.ui.pet.PetWelcomePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.lifecycle.Lifecycle

class PetInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = PetPreferencesStore(InstrumentationRegistry.getInstrumentation().targetContext)
    @Before fun resetPet() = runBlocking {
        store.setEnabled(true); store.setAddress("同学"); store.setFrequency(PetFrequency.QUIET)
        store.setWelcome(PetWelcomePolicy.EVERY_ENTRY)
    }
    private fun awaitPet() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("schedule-pet").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun petSpeaksAndMovesWithinContent() {
        awaitPet()
        compose.onNodeWithTag("schedule-pet").performClick()
        compose.onNodeWithTag("pet-chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭聊天").performTouchInput { click() }
        awaitPet()
        val before = compose.onNodeWithTag("schedule-pet").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("schedule-pet").performTouchInput { swipe(center, center + Offset(100f, -100f), 500) }
        val after = compose.onNodeWithTag("schedule-pet").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(after.left != before.left || after.top != before.top)
        compose.onNodeWithTag("pet-bubble").assertDoesNotExist()
    }
    @Test fun petDoesNotTakeOverCourseCreationOrWeekSwipe() {
        awaitPet()
        compose.onNodeWithContentDescription("添加课程").performClick()
        compose.onNodeWithText("添加课程").assertIsDisplayed()
        compose.onNodeWithTag("schedule-pet").assertDoesNotExist()
        pressBack()
        if (compose.onAllNodesWithText("载入示例课表").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("载入示例课表").performClick()
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("schedule-weeks").fetchSemanticsNodes().isNotEmpty() }
        val header = compose.onNodeWithContentDescription("选择周次").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        val week = Regex("第(\\d+)周").find(header)!!.groupValues[1].toInt()
        compose.onNodeWithTag("schedule-weeks").performTouchInput { if (week == 1) swipeLeft() else swipeRight() }
        compose.onNodeWithText("第${if (week == 1) 2 else week - 1}周").assertIsDisplayed()
        val cards = compose.onAllNodesWithText("路基路面工程")
        val visible = cards.fetchSemanticsNodes().indices.first { cards[it].isDisplayed() }
        cards[visible].performClick()
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onNodeWithTag("schedule-pet").assertDoesNotExist()
        pressBack()
        awaitPet()
    }
    @Test fun disablePetAndConfigureFrequencyAndAddress() {
        awaitPet()
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("课表小宠物").performScrollTo().performClick()
        compose.onNodeWithText("互动与外观").performClick()
        compose.onNodeWithText("显示课表小宠物").performScrollTo().performClick()
        compose.onNodeWithText("活泼").performScrollTo().performClick()
        compose.onNodeWithText("活泼").assertIsSelected()
        compose.onNodeWithText("宠物怎么称呼你").performScrollTo().performClick()
        compose.onNodeWithText("自定义称呼，最多 20 字").performTextClearance()
        compose.onNodeWithText("自定义称呼，最多 20 字").performTextInput("小贝")
        compose.onNodeWithText("确定").performClick()
        // DataStore 异步回写；弹窗/键盘收起后列表位置也可能变化。
        compose.waitUntil(5000) { compose.onAllNodesWithText("小贝").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("小贝").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performTouchInput { click() }
        compose.onNodeWithContentDescription("返回").performTouchInput { click() }
        compose.onNode(hasText("课表") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("schedule-pet").assertDoesNotExist()
    }
    @Test fun pausingStopsPetAndResumingRestoresItsPosition() {
        awaitPet()
        compose.onNodeWithTag("schedule-pet").performTouchInput { swipe(center, center + Offset(40f, -100f), 400) }
        val before = compose.onNodeWithTag("schedule-pet").fetchSemanticsNode().boundsInRoot
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.onNodeWithTag("schedule-pet").assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitPet()
        val after = compose.onNodeWithTag("schedule-pet").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(before.left, after.left, 1f)
        org.junit.Assert.assertEquals(before.top, after.top, 1f)
    }
    @Test fun weekFlipAnimatesPetBackIntoContent() {
        awaitPet()
        if (compose.onAllNodesWithText("载入示例课表").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("载入示例课表").performClick()
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("schedule-weeks").fetchSemanticsNodes().isNotEmpty() }
        val header = compose.onNodeWithContentDescription("选择周次").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        val week = Regex("第(\\d+)周").find(header)!!.groupValues[1].toInt()
        compose.onNodeWithTag("schedule-weeks").performTouchInput { if (week == 1) swipeLeft() else swipeRight() }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        // 入场动画结束后宠物必须完整回到屏幕内，不能停在屏幕外或分界线外
        compose.waitUntil(15_000) {
            val nodes = compose.onAllNodesWithTag("schedule-pet").fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes[0].boundsInRoot.let {
                it.left >= 0f && it.top >= 0f && it.right <= root.right && it.bottom <= root.bottom
            }
        }
    }
    @Test fun petMovesOnItsOwnWithoutInteraction() {
        awaitPet()
        val before = compose.onNodeWithTag("schedule-pet").fetchSemanticsNode().boundsInRoot
        compose.waitUntil(45_000) {
            val now = compose.onAllNodesWithTag("schedule-pet").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            now != null && (now.left != before.left || now.top != before.top)
        }
    }
}
