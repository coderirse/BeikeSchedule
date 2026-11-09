package com.caeamer.beikeschedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.ui.pet.PetBubble
import com.caeamer.beikeschedule.ui.pet.PetMessage
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import org.junit.Rule
import org.junit.Test

class PetBubbleTouchTest {
    @get:Rule val compose = createComposeRule()
    private fun showBubble() {
        compose.setContent {
            var clicks by remember { mutableIntStateOf(0) }
            var dismisses by remember { mutableIntStateOf(0) }
            BeikeScheduleTheme {
                Column {
                    Box(Modifier.width(236.dp).height(200.dp)) {
                        Box(Modifier.fillMaxSize().testTag("under-bubble").clickable { clicks++ })
                        PetBubble(PetMessage("test", "同学，今天也给你加油呀～"), false, 0.5f,
                            Modifier.fillMaxWidth(), onDismiss = { dismisses++ })
                    }
                    Text("底层点击：$clicks")
                    Text("关闭点击：$dismisses")
                }
            }
        }
    }
    @Test fun bubbleBodyPassesActualTouchToTheUnderlyingPage() {
        showBubble()
        compose.onNodeWithTag("pet-bubble").performTouchInput { click(Offset(20f, center.y)) }
        compose.onNodeWithText("底层点击：1").assertIsDisplayed()
    }
    @Test fun closeButtonKeepsItsOwnTouchTarget() {
        showBubble()
        compose.onNodeWithContentDescription("关闭宠物气泡").performTouchInput { click() }
        compose.onNodeWithText("关闭点击：1").assertIsDisplayed()
        compose.onNodeWithText("底层点击：0").assertIsDisplayed()
    }
}
