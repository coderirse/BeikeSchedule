package com.caeamer.beikeschedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import com.caeamer.beikeschedule.ui.todo.TodoScreen
import com.caeamer.beikeschedule.ui.todo.WeekdaySelector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class TodoInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearTestTodos() = runBlocking { AppDatabase.get(context).todoDao().clear() }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun weekdaysRespondToActualTouch() {
        compose.setContent {
            var selected by remember { mutableStateOf("1111100") }
            BeikeScheduleTheme {
                Box(Modifier.padding(horizontal = 24.dp)) { WeekdaySelector(selected) { selected = it } }
            }
        }
        compose.onNodeWithContentDescription("周六").assertIsOff().performTouchInput { click() }
        compose.onNodeWithContentDescription("周六").assertIsOn().performTouchInput { click() }
        compose.onNodeWithContentDescription("周六").assertIsOff()
        compose.onNodeWithContentDescription("周日").assertIsOff().performTouchInput { click() }
        compose.onNodeWithContentDescription("周日").assertIsOn()
    }

    @Test fun farFutureTodoIsAccessibleAndEditable() {
        runBlocking {
            AppDatabase.get(context).todoDao().upsert(TodoEntity(
                title = "下个月交报告", time = "10:00", repeatMode = TodoEntity.REPEAT_ONCE,
                date = LocalDate.now().plusDays(35).toString(),
            ))
        }
        compose.setContent { BeikeScheduleTheme { TodoScreen() } }
        awaitText("近期没有安排")
        compose.onNodeWithText("近期没有安排").assertIsDisplayed()
        compose.onNodeWithText("查看全部日程").performClick()
        compose.onNodeWithText("下个月交报告").assertIsDisplayed().performClick()
        compose.onNodeWithText("编辑日程").assertIsDisplayed()
    }

    @Test fun onlyTodaysOccurrenceHasAnInteractiveCheckbox() {
        runBlocking {
            AppDatabase.get(context).todoDao().upsert(TodoEntity(title = "每天晨读", time = "08:00"))
        }
        compose.setContent { BeikeScheduleTheme { TodoScreen() } }
        awaitText("每天晨读")
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable()).performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOn()
        compose.onAllNodesWithContentDescription("未来安排，仅预览").onFirst().assertIsDisplayed()
    }

    @Test fun savingNewTodoLocatesItInAllTodos() {
        compose.setContent { BeikeScheduleTheme { TodoScreen() } }
        awaitText("还没有日程")
        compose.onNodeWithContentDescription("新增日程").performClick()
        compose.onNodeWithText("事项").performTextInput("保存定位测试")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        awaitText("每天 · 已保存")
        compose.onNodeWithText("保存定位测试").assertIsDisplayed()
        compose.onNodeWithText("每天 · 已保存").assertIsDisplayed()
    }

    @Test fun changingOnlyMinutesKeepsHourThroughConfirmSaveAndReopen() {
        runBlocking {
            AppDatabase.get(context).todoDao().upsert(TodoEntity(title = "时间回归", time = "08:00"))
        }
        compose.setContent { BeikeScheduleTheme { TodoScreen() } }
        awaitText("时间回归")
        compose.onAllNodesWithText("时间回归").onFirst().performClick()
        compose.onNodeWithText("时间：08:00").performScrollTo().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(30)
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("时间：08:30").assertIsDisplayed()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        awaitText("每天 · 已保存")
        val saved = runBlocking { AppDatabase.get(context).todoDao().observeAll().first().single() }
        org.junit.Assert.assertEquals("08:30", saved.time)
        compose.onNodeWithText("时间回归").performClick()
        compose.onNodeWithText("时间：08:30").performScrollTo().performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("时间：08:30").assertIsDisplayed()
    }

    @Test fun unchangedReminderLeadTimeDoesNotResetOnConfirm() {
        runBlocking {
            AppDatabase.get(context).todoDao().upsert(TodoEntity(title = "提前量回归", time = "08:30", remindMinutes = 45))
        }
        compose.setContent { BeikeScheduleTheme { TodoScreen() } }
        awaitText("提前量回归")
        compose.onAllNodesWithText("提前量回归").onFirst().performClick()
        compose.onNodeWithText("提前 45 分钟").performScrollTo().performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("提前 45 分钟").assertIsDisplayed()
    }

    @Test fun confirmingDuringHourAnimationCommitsTheHighlightedHour() {
        runBlocking {
            AppDatabase.get(context).todoDao().upsert(TodoEntity(title = "快速确认回归", time = "08:30"))
        }
        compose.setContent { BeikeScheduleTheme { TodoScreen() } }
        awaitText("快速确认回归")
        compose.onAllNodesWithText("快速确认回归").onFirst().performClick()
        compose.onNodeWithText("时间：08:30").performScrollTo().performClick()
        compose.onNode(hasText("08") and isSelected()).assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("09").performTouchInput { click() }
        var frames = 0
        while (compose.onAllNodes(hasText("09") and isSelected()).fetchSemanticsNodes().isEmpty() && frames++ < 120) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNode(hasText("09") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("确定").performClick()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("时间：09:30").assertIsDisplayed()
    }

    @Test fun cancellingTimeChangesKeepsTheOriginalTime() {
        runBlocking {
            AppDatabase.get(context).todoDao().upsert(TodoEntity(title = "取消回归", time = "23:59"))
        }
        compose.setContent { BeikeScheduleTheme { TodoScreen() } }
        awaitText("取消回归")
        compose.onAllNodesWithText("取消回归").onFirst().performClick()
        compose.onNodeWithText("时间：23:59").performScrollTo().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        compose.onAllNodesWithText("取消").onLast().performClick()
        compose.onNodeWithText("时间：23:59").assertIsDisplayed()
    }

    @Test fun reminderBoundsAreConfirmedAndSavedWithoutAnIndexOffset() {
        runBlocking {
            AppDatabase.get(context).todoDao().upsert(TodoEntity(title = "提醒边界", time = "08:30", remindMinutes = 45))
        }
        compose.setContent { BeikeScheduleTheme { TodoScreen() } }
        awaitText("提醒边界")
        compose.onAllNodesWithText("提醒边界").onFirst().performClick()
        compose.onNodeWithText("提前 45 分钟").performScrollTo().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(119)
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("提前 120 分钟").assertIsDisplayed().performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("提前 120 分钟").assertIsDisplayed().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(0)
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("提前 1 分钟").assertIsDisplayed()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        awaitText("每天 · 已保存")
        val saved = runBlocking { AppDatabase.get(context).todoDao().observeAll().first().single() }
        org.junit.Assert.assertEquals("08:30", saved.time)
        org.junit.Assert.assertEquals(1, saved.remindMinutes)
    }
}
