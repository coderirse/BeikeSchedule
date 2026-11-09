package com.caeamer.beikeschedule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.ui.pet.PetPreferencesStore
import com.caeamer.beikeschedule.ui.pet.PetFrequency
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
    }
    private fun awaitPet() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("schedule-pet").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun petSpeaksAndMovesWithinContent() {
        awaitPet()
        compose.onNodeWithTag("schedule-pet").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pet-bubble").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-bubble").assertIsDisplayed()
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
        compose.onNodeWithText("显示课表小宠物").performScrollTo().performClick()
        compose.onNodeWithText("活泼").performScrollTo().performClick()
        compose.onNodeWithText("活泼").assertIsSelected()
        compose.onNodeWithText("宠物怎么称呼你").performScrollTo().performClick()
        compose.onNodeWithText("自定义称呼，最多 20 字").performTextClearance()
        compose.onNodeWithText("自定义称呼，最多 20 字").performTextInput("小贝")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("小贝").assertIsDisplayed()
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
}
