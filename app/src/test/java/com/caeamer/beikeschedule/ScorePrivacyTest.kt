package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.pref.ScorePrivacy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成绩隐私会话态：默认隐藏，切换后保持，退到后台复位隐藏。 */
class ScorePrivacyTest {

    @After
    fun tearDown() {
        ScorePrivacy.hide()
    }

    @Test
    fun `默认隐藏`() {
        assertTrue(ScorePrivacy.hidden.value)
    }

    @Test
    fun `切换一次变为显示，再切换回隐藏`() {
        ScorePrivacy.toggle()
        assertFalse(ScorePrivacy.hidden.value)
        ScorePrivacy.toggle()
        assertTrue(ScorePrivacy.hidden.value)
    }

    @Test
    fun `显示后复位回隐藏`() {
        ScorePrivacy.toggle()
        assertFalse(ScorePrivacy.hidden.value)
        ScorePrivacy.hide()
        assertTrue(ScorePrivacy.hidden.value)
    }
}
