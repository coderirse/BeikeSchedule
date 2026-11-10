package com.caeamer.beikeschedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.ui.pet.*
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

class PetPoseRenderTest {
    @get:Rule val compose = createComposeRule()
    @Test fun decodedRestingFramesRenderInBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val atlas = runBlocking { PetAtlasCache.loadPoses(context) }
        org.junit.Assert.assertEquals(2, atlas.regions.size)
        var dark by mutableStateOf(false)
        compose.setContent {
            BeikeScheduleTheme(darkTheme = dark) {
                Row(Modifier.testTag("poses").background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    listOf(PetPosture.PERCH, PetPosture.LEAN_LEFT, PetPosture.LEAN_RIGHT).forEach { posture ->
                        Column {
                            PetRestSprite(atlas, posture, Modifier.size(80.dp, if (posture == PetPosture.PERCH) 56.dp else 80.dp))
                            Box(Modifier.width(80.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        }
        listOf(false, true).forEach { theme ->
            compose.runOnIdle { dark = theme }
            compose.onNodeWithTag("poses").captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.getExternalFilesDir(null), "pet-poses-${if (theme) "dark" else "light"}.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
