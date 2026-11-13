package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.ui.pet.PetViewModel
import com.caeamer.beikeschedule.ui.pet.PetWelcomeLines
import com.caeamer.beikeschedule.ui.pet.proactivePrompt
import com.caeamer.beikeschedule.ui.pet.proactiveTopic
import com.caeamer.beikeschedule.ui.pet.tooSimilar
import com.caeamer.beikeschedule.ui.pet.ai.AiMessage
import com.caeamer.beikeschedule.ui.pet.ai.AiProvider
import com.caeamer.beikeschedule.ui.pet.ai.AiVendor
import com.caeamer.beikeschedule.ui.pet.ai.PetAiState
import com.caeamer.beikeschedule.ui.pet.ai.SearchMode
import com.caeamer.beikeschedule.ui.pet.ai.hotspotAvailable
import com.caeamer.beikeschedule.ui.pet.ai.recentBubbleLines
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PetLivelinessTest {
    private fun configured(vendor: AiVendor, mode: SearchMode, searchKey: String = ""): PetAiState {
        val provider = AiProvider(vendor = vendor, apiKey = "key", model = "model",
            baseUrl = if (vendor == AiVendor.CUSTOM) "https://example.com/v1" else vendor.baseUrl)
        return PetAiState(providers = listOf(provider), activeProvider = provider.id, searchMode = mode, searchKey = searchKey)
    }

    @Test fun `topic rotation never repeats last and respects capabilities`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val last = listOf("arrangement", "study", "hotspot", "casual", "memory").random(random)
            val topic = proactiveTopic(last, searchEnabled = true, hasLearning = true, hasMemories = true, random)
            assertNotEquals(last, topic)
        }
        repeat(100) { seed ->
            assertFalse(proactiveTopic(null, searchEnabled = false, hasLearning = true, hasMemories = true, Random(seed)) == "hotspot")
            val plain = proactiveTopic(null, searchEnabled = false, hasLearning = false, hasMemories = false, Random(seed))
            assertEquals("casual", plain)
            assertFalse(proactiveTopic(null, searchEnabled = true, hasLearning = false, hasMemories = false, Random(seed)) in setOf("arrangement", "study", "memory"))
        }
        val seen = (0..400).map { proactiveTopic(null, searchEnabled = true, hasLearning = true, hasMemories = true, Random(it)) }.toSet()
        assertEquals(setOf("arrangement", "study", "hotspot", "casual", "memory"), seen)
    }

    @Test fun `similarity catches rephrased duplicates but not new content`() {
        assertTrue(tooSimilar("今天也要加油呀，慢慢来就好", "今天也要加油呀，慢慢来就好"))
        assertTrue(tooSimilar("今天也要加油呀，慢慢来就好～", "今天也要加油呀！慢慢来就好。"))
        assertFalse(tooSimilar("今天也要加油呀，慢慢来就好", "明天下午有实验课，记得带报告"))
        assertFalse(tooSimilar("", ""))
        assertFalse(tooSimilar("好", "好"))
    }

    @Test fun `welcome lines avoid recent ids and fall back when exhausted`() {
        val visit = PetViewModel.Welcome(id = 1, absenceMillis = 0, firstVisit = false)
        val hour = 9
        val first = PetWelcomeLines.message("同学", visit, hour)
        assertTrue(first.id.startsWith("welcome-line:"))
        val poolSize = 3
        val used = (0 until poolSize - 1).map { "welcome-line:$it" }.toSet()
        val remaining = (0 until 20).map { PetWelcomeLines.message("同学", visit, hour, used).id }.toSet()
        assertEquals(setOf("welcome-line:${poolSize - 1}"), remaining)
        val all = (0 until poolSize).map { "welcome-line:$it" }.toSet()
        assertTrue(PetWelcomeLines.message("同学", visit, hour, all).id.startsWith("welcome-line:"))
    }

    @Test fun `hotspot availability follows mode vendor and key`() {
        assertFalse(hotspotAvailable(PetAiState()))
        assertFalse(hotspotAvailable(configured(AiVendor.MIMO, SearchMode.OFF)))
        assertTrue(hotspotAvailable(configured(AiVendor.MIMO, SearchMode.PROVIDER)))
        assertTrue(hotspotAvailable(configured(AiVendor.DEEPSEEK, SearchMode.PROVIDER)))
        assertFalse(hotspotAvailable(configured(AiVendor.CUSTOM, SearchMode.PROVIDER)))
        assertFalse(hotspotAvailable(configured(AiVendor.MIMO, SearchMode.DEEPSEEK)))
        assertTrue(hotspotAvailable(configured(AiVendor.MIMO, SearchMode.DEEPSEEK, searchKey = "search-key")))
    }

    @Test fun `recent bubble lines keep newest assistant text only`() {
        val rows = (1..10).map { AiMessage(conversation = "c", role = if (it % 2 == 0) "assistant" else "user", text = "消息$it") }
        val lines = recentBubbleLines(rows, limit = 3)
        assertEquals(listOf("消息6", "消息8", "消息10"), lines)
        assertTrue(recentBubbleLines(emptyList()).isEmpty())
        val long = AiMessage(conversation = "c", role = "assistant", text = "字".repeat(500))
        assertEquals(200, recentBubbleLines(listOf(long)).single().length)
        assertTrue(recentBubbleLines(listOf(AiMessage(conversation = "c", role = "assistant", text = "  "))).isEmpty())
    }

    @Test fun `topic prompts are distinct and hotspot requires search`() {
        val prompts = listOf("arrangement", "study", "hotspot", "casual", "memory").map { proactivePrompt(it, "小贝") }
        assertEquals(prompts.size, prompts.toSet().size)
        assertTrue(prompts.all { it.contains("小贝") })
        assertTrue(proactivePrompt("hotspot", "小贝").contains("搜索"))
        assertTrue(proactivePrompt("hotspot", "小贝").contains("编造"))
    }
}