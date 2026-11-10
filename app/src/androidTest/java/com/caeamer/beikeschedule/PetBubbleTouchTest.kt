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
import com.caeamer.beikeschedule.ui.pet.PetTailSide
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import org.junit.Rule
import org.junit.Test

class PetBubbleTouchTest {
    @get:Rule val compose = createComposeRule()
    private fun showBubble(tail: State<PetTailSide>? = null) {
        compose.setContent {
            var clicks by remember { mutableIntStateOf(0) }
            var dismisses by remember { mutableIntStateOf(0) }
            BeikeScheduleTheme {
                Column {
                    Box(Modifier.width(236.dp).height(200.dp)) {
                        Box(Modifier.fillMaxSize().testTag("under-bubble").clickable { clicks++ })
                        PetBubble(PetMessage("test", "同学，今天也给你加油呀～"), false, 0.5f,
                            Modifier.fillMaxWidth(), onDismiss = { dismisses++ }, tailSide = tail?.value ?: PetTailSide.BOTTOM)
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
    @Test fun changingTailDirectionKeepsBubbleDimensionsStable() {
        val tail = mutableStateOf(PetTailSide.TOP)
        showBubble(tail)
        val original = compose.onNodeWithTag("pet-bubble").fetchSemanticsNode().boundsInRoot
        PetTailSide.entries.forEach { side ->
            compose.runOnIdle { tail.value = side }
            val bounds = compose.onNodeWithTag("pet-bubble").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertEquals(original.height, bounds.height, 0.1f)
            org.junit.Assert.assertEquals(original.width, bounds.width, 0.1f)
        }
    }
}
