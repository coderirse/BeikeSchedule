package com.caeamer.beikeschedule

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import org.junit.Rule
import org.junit.Test

class NavigationInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabKeepsScrollAndSystemBackReturnsToSchedule() {
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("主题").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("课表") and hasClickAction()).performClick()
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("主题").assertIsDisplayed()
        pressBack()
        compose.onNode(hasText("课表") and hasClickAction()).assertIsSelected()
    }

    @Test fun academicSectionAndTodoViewSurviveTabSwitch() {
        compose.onNode(hasText("教务") and hasClickAction()).performClick()
        compose.onNodeWithText("日程").performClick()
        compose.onNodeWithText("全部日程").performClick()
        compose.onNode(hasText("课表") and hasClickAction()).performClick()
        compose.onNode(hasText("教务") and hasClickAction()).performClick()
        compose.onNodeWithText("全部日程").assertIsSelected()
        compose.onNodeWithText("考试").performClick()
        compose.onNodeWithText("日程").performClick()
        compose.onNodeWithText("全部日程").assertIsSelected()
    }

    @Test fun refreshEntersItsScopeDirectlyAndBackKeepsTheOriginSection() {
        compose.onNode(hasText("教务") and hasClickAction()).performClick()
        compose.onNodeWithText("考试").performClick()
        compose.onNodeWithContentDescription("刷新考试").performClick()
        compose.onNodeWithText("刷新考试").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("考试").assertIsSelected()
        compose.onNodeWithText("成绩").performClick()
        compose.onNodeWithContentDescription("刷新成绩").performClick()
        compose.onNodeWithText("刷新成绩").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("成绩").assertIsSelected()
    }
}
