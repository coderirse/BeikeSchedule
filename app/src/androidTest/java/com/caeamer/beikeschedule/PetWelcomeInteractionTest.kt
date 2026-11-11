package com.caeamer.beikeschedule

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.ui.pet.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PetWelcomeInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = PetPreferencesStore(InstrumentationRegistry.getInstrumentation().targetContext)
    @Before fun prepare(): Unit = runBlocking {
        store.setEnabled(true); store.setWelcome(PetWelcomePolicy.EVERY_ENTRY)
        store.setChatter(false); store.setCourseHints(false); store.setExamHints(false)
        // Activity 已启动，显式产生一次新的可见会话，避免与首帧 DataStore 读盘竞争。
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }
    @After fun restore() = runBlocking {
        store.setWelcome(PetWelcomePolicy.EVERY_ENTRY); store.setChatter(true)
        store.setCourseHints(true); store.setExamHints(true)
    }
    private fun awaitBubble() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pet-bubble").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pet-bubble").assertIsDisplayed()
    }
    private fun dismiss() { compose.onNodeWithContentDescription("关闭宠物气泡").performTouchInput { click() } }

    @Test fun returningFromTabGreetsEvenWhenAmbientChatterIsOff() {
        awaitBubble(); dismiss()
        compose.onNode(hasText("我的") and hasClickAction()).performTouchInput { click() }
        compose.onNode(hasText("课表") and hasClickAction()).performTouchInput { click() }
        awaitBubble()
    }
    @Test fun foregroundResumeGreetsButClosingCourseEditorDoesNot() {
        awaitBubble(); dismiss()
        compose.onNodeWithContentDescription("添加课程").performTouchInput { click() }
        compose.onNodeWithTag("schedule-pet").assertDoesNotExist()
        pressBack()
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        compose.onNodeWithTag("pet-bubble").assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitBubble()
    }
    @Test fun welcomeSettingCanDisableOrThrottleEntryIndependently() {
        awaitBubble(); dismiss()
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("课表小宠物").performScrollTo().performClick()
        compose.onNodeWithText("互动与外观").performClick()
        compose.onNodeWithText("间隔 5 分钟").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("间隔 5 分钟").assertIsSelected()
        pressBack(); pressBack()
        compose.onNode(hasText("课表") and hasClickAction()).performClick()
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        compose.onNodeWithTag("pet-bubble").assertDoesNotExist()
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("课表小宠物").performScrollTo().performClick()
        compose.onNodeWithText("互动与外观").performClick()
        compose.onNodeWithText("关闭欢迎").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("关闭欢迎").assertIsSelected()
        pressBack(); pressBack()
        compose.onNode(hasText("课表") and hasClickAction()).performClick()
        compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        compose.onNodeWithTag("pet-bubble").assertDoesNotExist()
        compose.onNodeWithTag("schedule-pet").performTouchInput { click() }
        compose.onNodeWithTag("pet-chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭聊天").performTouchInput { click() }
    }
}
