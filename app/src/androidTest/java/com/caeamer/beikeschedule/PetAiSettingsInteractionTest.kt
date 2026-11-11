package com.caeamer.beikeschedule

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.ui.pet.*
import com.caeamer.beikeschedule.ui.pet.ai.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PetAiSettingsInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = PetAiStore.get(context)
    private lateinit var original: PetAiState
    private lateinit var petOriginal: PetPreferences
    @Before fun prepare() = runBlocking {
        original = withTimeout(10000) { store.state.filterNotNull().first() }
        petOriginal = PetPreferencesStore(context).preferences.first()
        store.update { PetAiState() }
        PetPreferencesStore(context).setEnabled(true)
    }
    @After fun restore() = runBlocking {
        store.update { original }
        PetPreferencesStore(context).setEnabled(petOriginal.enabled)
    }
    private fun settings() {
        compose.onNode(hasText("我的") and hasClickAction()).performTouchInput { click() }
        compose.onNodeWithText("显示课表小宠物").assertDoesNotExist()
        compose.onNodeWithText("课表小宠物").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("模型与联网").assertIsDisplayed()
    }
    @Test fun singleSettingsEntryKeepsBackNavigationAndOldControls() {
        settings()
        compose.onNodeWithText("互动与外观").performTouchInput { click() }
        compose.onNodeWithText("显示课表小宠物").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("记忆与数据").assertIsDisplayed()
        pressBack()
        compose.onNode(hasText("我的") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("显示课表小宠物").assertDoesNotExist()
    }
    @Test fun customProviderSavesEncryptedAndSurvivesReopening() {
        settings(); compose.onNodeWithText("模型与联网").performClick()
        compose.onNodeWithText("添加供应商").performClick()
        compose.onNodeWithText("API Key").performScrollTo().performTextInput("must-clear-on-vendor-change")
        compose.onNodeWithText("自定义 OpenAI 兼容").performScrollTo().performClick()
        compose.onNodeWithText("供应商名称").performScrollTo().performTextReplacement("测试供应商")
        compose.onNodeWithText("HTTPS API 地址").performScrollTo().performTextReplacement("https://testing.example/v1")
        compose.onNodeWithText("API Key").performScrollTo().performTextInput("test-only-secret")
        compose.onNodeWithText("模型名").performScrollTo().performTextInput("test-model")
        compose.onNodeWithText("保存供应商").performClick()
        compose.waitUntil(5000) { store.state.value?.provider?.model == "test-model" }
        assertEquals("test-only-secret", store.state.value!!.provider!!.apiKey)
        val encrypted = File(context.noBackupFilesDir, "pet_ai/state.enc").readBytes()
        assertEquals(1.toByte(), encrypted.first()); assertFalse(encrypted.toString(Charsets.UTF_8).contains("test-only-secret"))
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("模型与联网").performClick()
        compose.onNodeWithText("测试供应商").assertIsDisplayed()
        compose.onNodeWithText("test-model").assertIsDisplayed()
    }
    @Test fun personaMemoryAndPermissionsAreEditableAndPersistent() {
        settings(); compose.onNodeWithText("人格与称呼").performClick()
        compose.onNodeWithText("宠物名字").performScrollTo().performTextReplacement("小蓝")
        compose.onNodeWithText("自定义人格").performScrollTo().performTextReplacement("说话简洁，偶尔吐槽，认真核对事实。")
        compose.onNodeWithText("保存人格").performScrollTo().performClick()
        compose.waitUntil(5000) { store.state.value?.petName == "小蓝" }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("记忆与数据").performClick()
        compose.onNodeWithText("成绩", substring = false).performScrollTo().performTouchInput { click() }
        compose.waitUntil(5000) { LearningScope.GRADES in store.state.value!!.grants }
        compose.onNodeWithText("添加记忆").performScrollTo().performClick()
        if (compose.onAllNodesWithText("偏好或约定").fetchSemanticsNodes().isEmpty()) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "pet-ai-memory-click.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        compose.onNodeWithText("偏好或约定").performTextInput("喜欢简短的回答")
        compose.onNodeWithText("保存记忆").performClick()
        compose.waitUntil(5000) { store.state.value!!.memories.any { it.text == "喜欢简短的回答" } }
        compose.onNodeWithText("喜欢简短的回答").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("记住偏好与约定").performScrollTo().performTouchInput { click() }
        compose.waitUntil(5000) { !store.state.value!!.memoryEnabled }
        assertEquals(1, store.state.value!!.memories.size)
        compose.onNodeWithText("删除记忆").performScrollTo().performClick()
        compose.waitUntil(5000) { store.state.value!!.memories.isEmpty() }
    }
    @Test fun actualPetTouchOpensExpandableChatAndClosingRestoresPet() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("schedule-pet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule-pet").performTouchInput { click() }
        compose.onNodeWithTag("pet-chat").assertIsDisplayed()
        compose.onNodeWithText("去宠物设置").assertIsDisplayed()
        compose.onNodeWithTag("schedule-pet").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开聊天").performTouchInput { click() }
        compose.onNodeWithContentDescription("收起聊天").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭聊天").performTouchInput { click() }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("schedule-pet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule-pet").assertIsDisplayed()
    }
}
