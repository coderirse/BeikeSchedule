package com.caeamer.beikeschedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsProperties
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import com.caeamer.beikeschedule.ui.todo.WheelColumn
import org.junit.Rule
import org.junit.Test

class WheelPickerInteractionTest {
    @get:Rule val compose = createComposeRule()

    private fun showWheels(hour: Int = 8, minute: Int = 30) {
        compose.setContent {
            var selectedHour by remember { mutableIntStateOf(hour) }
            var selectedMinute by remember { mutableIntStateOf(minute) }
            BeikeScheduleTheme {
                Column {
                    Row {
                        WheelColumn((0..23).map { "%02d".format(it) }, selectedHour,
                            { selectedHour = it }, Modifier.weight(1f).testTag("hours"))
                        WheelColumn((0..59).map { "%02d".format(it) }, selectedMinute,
                            { selectedMinute = it }, Modifier.weight(1f).testTag("minutes"))
                    }
                    Text("结果：%02d:%02d".format(selectedHour, selectedMinute))
                }
            }
        }
    }

    @Test fun initialSelectionMatchesVisibleCenterWithoutTouchingWheels() {
        showWheels()
        compose.onNode(hasText("08") and isSelected()).assertIsDisplayed()
        compose.onNode(hasText("30") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("结果：08:30").assertIsDisplayed()
    }

    @Test fun changingMinutesPreservesUntouchedHour() {
        showWheels(minute = 0)
        compose.onNodeWithTag("minutes").performScrollToIndex(30)
        compose.onNode(hasText("30") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("结果：08:30").assertIsDisplayed()
    }

    @Test fun tappingHourPreservesUntouchedMinute() {
        showWheels()
        compose.onNode(hasText("09") and hasAnyAncestor(androidx.compose.ui.test.hasTestTag("hours")))
            .performTouchInput { click() }
        compose.onNodeWithText("结果：09:30").assertIsDisplayed()
    }

    @Test fun lastTimeValuesAreCenteredWithoutResetting() {
        showWheels(hour = 23, minute = 59)
        compose.onNodeWithText("结果：23:59").assertIsDisplayed()
        compose.onNode(hasText("23") and isSelected()).assertIsDisplayed()
        compose.onNode(hasText("59") and isSelected()).assertIsDisplayed()
    }

    @Test fun draggingMinutesKeepsVisibleAndReportedValueInSync() {
        showWheels()
        compose.onNodeWithTag("minutes").performTouchInput { swipeUp() }
        val selected = compose.onNode(isSelected() and hasAnyAncestor(hasTestTag("minutes")))
            .fetchSemanticsNode().config[SemanticsProperties.Text].first().text.toInt()
        org.junit.Assert.assertTrue(selected > 30)
        compose.onNodeWithText("结果：08:%02d".format(selected)).assertIsDisplayed()
    }

    @Test fun restoredWheelPositionIsNotOverwrittenByItsOriginalIndex() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var hour by remember { mutableIntStateOf(8) }
            BeikeScheduleTheme {
                Column {
                    WheelColumn((0..23).map { it.toString() }, 8, { hour = it }, Modifier.testTag("restored"))
                    Text("小时：$hour")
                }
            }
        }
        compose.onNodeWithTag("restored").performScrollToIndex(23)
        compose.onNodeWithText("小时：23").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("小时：23").assertIsDisplayed()
    }
}
