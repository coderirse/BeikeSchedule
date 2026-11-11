package com.caeamer.beikeschedule

import androidx.compose.ui.geometry.Offset
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.ui.pet.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class PetHintsTest {
    private val now = LocalDateTime.of(2026, 11, 9, 7, 50)
    private val course = CourseEntity(id = 1, taskId = "task", name = "高数", teacher = "", location = "【校本部】教学楼101",
        dayOfWeek = 1, startSection = 1, endSection = 2, weekBitmap = "0" + "1".repeat(20), colorIndex = 0, source = 0)
    private val facts = PetFacts(listOf(course), listOf(SectionTimeEntity(1, "08:00", "08:45")),
        SettingsStore.SemesterConfig(firstMonday = "2026-11-09"), emptyList(), 15)
    private fun exam(date: String = "2026-11-09", time: String = "08:30") = ExamEntity(kcdm = "e", kcmc = "力学",
        kslx = "期末", kssjms = "", ksrq = date, kssj = time, jssj = "10:00", cdmc = "教102", zwh = "", jkjsbz = "", kkyxmc = "", xnxq = "")

    @Test fun `course uses actual time and custom address`() {
        val hint = PetHints.resolve(facts, now, PetPreferences(address = "小贝" )).single()
        assertEquals(PetMessageKind.COURSE, hint.kind)
        assertTrue(hint.text.startsWith("小贝，"))
        assertTrue(hint.text.contains("10 分钟"))
        assertTrue(hint.text.contains("教学楼101"))
    }
    @Test fun `hidden occurrences and whole courses never generate hints`() {
        listOf(course.copy(hidden = true), course.copy(hiddenDates = "2026-11-09")).forEach {
            assertTrue(PetHints.resolve(facts.copy(courses = listOf(it)), now, PetPreferences()).isEmpty())
        }
    }
    @Test fun `holiday suppresses course hint`() {
        assertTrue(PetHints.resolve(facts.copy(semester = facts.semester.copy(holidays = listOf("2026-11-09"))), now, PetPreferences()).isEmpty())
    }
    @Test fun `past or distant classes are not announced`() {
        assertTrue(PetHints.resolve(facts, now.minusHours(2), PetPreferences()).isEmpty())
        assertTrue(PetHints.resolve(facts, now.plusMinutes(10), PetPreferences()).isEmpty())
    }
    @Test fun `pet switches independently suppress each category`() {
        val data = facts.copy(exams = listOf(exam()))
        assertEquals(listOf(PetMessageKind.EXAM), PetHints.resolve(data, now, PetPreferences(courseHints = false)).map { it.kind })
        assertEquals(listOf(PetMessageKind.COURSE), PetHints.resolve(data, now, PetPreferences(examHints = false)).map { it.kind })
        assertTrue(PetHints.resolve(data, now, PetPreferences(courseHints = false, examHints = false)).isEmpty())
    }
    @Test fun `urgent exam wins over class and stable ids survive reimport`() {
        assertEquals(PetMessageKind.EXAM, PetHints.resolve(facts.copy(exams = listOf(exam())), now, PetPreferences()).first().kind)
        assertEquals(PetHints.resolve(facts, now, PetPreferences()).first().id,
            PetHints.resolve(facts.copy(courses = listOf(course.copy(id = 222))), now, PetPreferences()).first().id)
    }
    @Test fun `past exams and exams beyond tomorrow are skipped`() {
        assertTrue(PetHints.resolve(facts.copy(courses = emptyList(), exams = listOf(exam(time = "07:00"), exam(date = "2026-11-11"))), now, PetPreferences()).isEmpty())
    }
    @Test fun `unknown exam time never invents countdown`() {
        val text = PetHints.resolve(facts.copy(courses = emptyList(), exams = listOf(exam(time = ""))), now, PetPreferences()).single().text
        assertTrue(text.contains("确认")); assertFalse(text.contains("分钟"))
    }
    @Test fun `local dialogue avoids recent lines and supports empty address`() = runBlocking {
        val provider = LocalPetDialogueProvider(Random(1))
        val first = provider.next(PetDialogueRequest("", emptySet()))
        val second = provider.next(PetDialogueRequest("", setOf(first.id)))
        assertNotEquals(first.id, second.id)
        assertFalse(first.text.startsWith("，"))
    }
    @Test fun `frequency ranges and default remain bounded`() {
        assertEquals(PetFrequency.MEDIUM, PetPreferences().frequency)
        PetFrequency.entries.forEach { frequency -> repeat(100) {
            val ms = frequency.delayMillis(Random(it))
            assertTrue(ms in frequency.minimumSeconds * 1000L..frequency.maximumSeconds * 1000L)
        } }
    }
    @Test fun `drag bounds reserve existing add button`() {
        val p = safePetPosition(Offset(999f, 999f), 300f, 500f, 64f, 80f)
        assertTrue(p.x in 0f..236f && p.y in 0f..436f)
        assertFalse(p.x + 64 > 220 && p.y + 64 > 420)
        assertEquals(Offset.Zero, safePetPosition(Offset(-10f, -20f), 300f, 500f, 64f, 80f))
    }
    @Test fun `bubble trim keeps whole sentences and marks hard cuts`() {
        assertEquals("今天也要加油呀", bubbleTrim("今天也要加油呀", 20))
        assertEquals("今天先把作业写完再说。", bubbleTrim("今天先把作业写完再说。然后去操场跑步顺便看看晚霞", 20))
        assertEquals("这个问题问得好？", bubbleTrim("这个问题问得好？后面还有话", 12))
        val early = "好。" + "后面是一整段没有标点的长话".repeat(4)
        assertEquals(early.take(20) + "…", bubbleTrim(early, 20))
        assertEquals("无标点".repeat(7) + "…", bubbleTrim("无标点".repeat(10), 21))
    }
}
