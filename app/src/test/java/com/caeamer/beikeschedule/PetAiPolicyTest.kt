package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.ui.pet.ai.*
import org.junit.Assert.*
import org.junit.Test

class PetAiPolicyTest {
    @Test fun firstPersonPreferencesAndDirectivesCountAsExplicit() {
        listOf("我喜欢简短回答", "我不喜欢早起", "我习惯晚上复习", "我希望你温柔一点", "记住我喜欢咖啡", "以后叫我老板", "我们约定每天打卡").forEach {
            assertTrue(it, explicitPreference(it))
        }
    }
    @Test fun questionsAndChatterDoNotCountAsExplicit() {
        listOf("你喜欢什么颜色", "你习惯几点睡觉", "今天天气怎么样", "希望明天不下雨", "帮我看看课表").forEach {
            assertFalse(it, explicitPreference(it))
        }
    }
    @Test fun historyBudgetKeepsNewestWholeMessagesInOrder() {
        val rows = (1..10).map { AiMessage(conversation = "c", role = if (it % 2 == 0) "assistant" else "user", text = "$it:" + "字".repeat(2997)) }
        val kept = budgetHistory(rows, 9000)
        assertEquals(listOf(rows[7], rows[8], rows[9]), kept)
    }
    @Test fun defaultBudgetDropsOldestBeyondLimit() {
        val rows = (1..5).map { AiMessage(conversation = "c", role = "user", text = "字".repeat(6000)) }
        val kept = budgetHistory(rows)
        assertEquals(rows.drop(1), kept)
    }
    @Test fun singleOversizedMessageIsStillKeptAndEmptyStaysEmpty() {
        val huge = AiMessage(conversation = "c", role = "user", text = "字".repeat(20000))
        assertEquals(listOf(huge), budgetHistory(listOf(huge), 100))
        assertTrue(budgetHistory(emptyList()).isEmpty())
    }
}