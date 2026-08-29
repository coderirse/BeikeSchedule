package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.model.SectionMap
import org.junit.Assert.assertEquals
import org.junit.Test

/** 大节映射单测。 */
class SectionMapTest {

    @Test
    fun `小节归属大节`() {
        assertEquals(0, SectionMap.bigIndexOf(1))
        assertEquals(0, SectionMap.bigIndexOf(2))
        assertEquals(1, SectionMap.bigIndexOf(3))
        assertEquals(4, SectionMap.bigIndexOf(9))
        assertEquals(5, SectionMap.bigIndexOf(11))
        assertEquals(5, SectionMap.bigIndexOf(12))
        // 13 节特殊加课归入第六大节
        assertEquals(5, SectionMap.bigIndexOf(13))
    }

    @Test
    fun `大节描述`() {
        assertEquals("第一大节", SectionMap.describeBigSections(1, 2))
        assertEquals("第二大节", SectionMap.describeBigSections(3, 4))
        assertEquals("第六大节", SectionMap.describeBigSections(11, 12))
        assertEquals("第六大节", SectionMap.describeBigSections(11, 13))
        assertEquals("第三~四大节", SectionMap.describeBigSections(5, 8))
    }
}
