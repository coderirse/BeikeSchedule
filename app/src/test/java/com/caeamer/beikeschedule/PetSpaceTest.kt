package com.caeamer.beikeschedule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.ui.pet.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class PetSpaceTest {
    private val now = LocalDateTime.of(2026, 11, 9, 7, 50)
    private val semester = SettingsStore.SemesterConfig(firstMonday = "2026-11-09")
    private val course = CourseEntity(id = 1, taskId = "t", name = "高数", teacher = "", location = "101",
        dayOfWeek = 1, startSection = 5, endSection = 6, weekBitmap = "0" + "1".repeat(20), colorIndex = 0, source = 0)
    private fun env(courses: List<CourseEntity> = listOf(course), config: SettingsStore.SemesterConfig = semester, week: Int = 1, inactive: Boolean = true) =
        petEnvironment(courses, config, week, (1..7).toList(), inactive, Rect(0f, 50f, 736f, 706f),
            36f, 56f, 1f, listOf(SectionTimeEntity(5, "13:30", "14:15")), now)

    @Test fun `environment matches grid insets sections and today`() {
        val e = env()
        assertEquals(Rect(37f, 251f, 135f, 349f), e.cards.single().rect)
        assertTrue(e.cards.single().perchable)
        assertEquals(8f, e.cards.single().weight)
        assertEquals(50f, e.headerBottom)
    }
    @Test fun `hidden dates and holidays are absent`() {
        assertTrue(env(listOf(course.copy(hidden = true))).cards.isEmpty())
        assertTrue(env(listOf(course.copy(hiddenDates = "2026-11-09"))).cards.isEmpty())
        assertTrue(env(config = semester.copy(holidays = listOf("2026-11-09"))).cards.isEmpty())
    }
    @Test fun `other weeks and weekdays cannot be perched`() {
        assertFalse(env(week = 2).cards.single().perchable)
        assertFalse(env(listOf(course.copy(dayOfWeek = 2))).cards.single().perchable)
    }
    @Test fun `conflicts occupy separate horizontal lanes`() {
        val cards = env(listOf(course, course.copy(id = 2, name = "英语"))).cards
        assertEquals(2, cards.size)
        assertEquals(48f, cards.first().rect.width)
        assertTrue(cards[0].rect.right <= cards[1].rect.left || cards[1].rect.right <= cards[0].rect.left)
    }
    @Test fun `inactive courses still block space when displayed`() {
        val c = course.copy(weekBitmap = "001")
        assertTrue(env(listOf(c)).cards.isEmpty())
        val displayed = env(listOf(c), inactive = false).cards.single()
        assertFalse(displayed.perchable); assertEquals(1f, displayed.weight)
    }
    @Test fun `today course supports only the pet above its top`() {
        val e = env()
        val stops = PetSpace.destinations(736f, 706f, 64f, 80f, e)
        val perched = stops.first { it.posture == PetPosture.PERCH && it.position.y + 64 == e.cards.single().rect.top }
        assertEquals(e.cards.single().rect.top, PetSpace.body(perched, 64f).bottom)
        assertTrue(PetSpace.free(perched.position, 736f, 706f, 64f, 80f, e, perched.posture))
    }
    @Test fun `all generated stops avoid courses header time labels and add button`() {
        val e = env()
        PetSpace.destinations(736f, 706f, 64f, 80f, e).forEach {
            assertTrue(PetSpace.free(it.position, 736f, 706f, 64f, 80f, e, it.posture))
            assertEquals(0f, PetSpace.score(PetSpace.body(it, 64f), e))
        }
    }
    @Test fun `ordinary automatic stops prefer distance from todays course`() {
        val e = PetEnvironment(listOf(PetObstacle(Rect(100f, 100f, 200f, 300f), 14f)), 50f, 30f)
        repeat(20) {
            val stop = PetSpace.choose(700f, 700f, 64f, 80f, e, Random(it))
            assertTrue(stop.position.x > 350f || stop.position.y > 400f)
        }
    }
    @Test fun `route travels around obstacle rather than through it`() {
        val e = PetEnvironment(listOf(PetObstacle(Rect(250f, 100f, 350f, 400f), 8f)))
        var from = Offset(100f, 200f)
        val target = Offset(450f, 200f)
        val path = PetSpace.route(from, target, 650f, 650f, 64f, 80f, e)
        assertTrue(path.size > 1); assertEquals(target, path.last())
        path.forEach { assertTrue(PetSpace.clearSegment(from, it, 650f, 650f, 64f, 80f, e)); from = it }
    }
    @Test fun `unreachable destination does not teleport through wall`() {
        val e = PetEnvironment(listOf(PetObstacle(Rect(250f, 0f, 350f, 650f), 8f)))
        assertTrue(PetSpace.route(Offset(100f, 200f), Offset(450f, 200f), 650f, 650f, 64f, 80f, e).isEmpty())
    }
    @Test fun `bubble chooses available blank space and stays in bounds`() {
        val e = env()
        val p = PetSpace.bubble(Rect(60f, 351f, 124f, 415f), Size(208f, 140f), 736f, 706f, 80f, e)
        assertEquals(0f, PetSpace.score(p.rect, e))
        assertTrue(p.rect.left >= 36 && p.rect.top >= 50 && p.rect.right <= 736 && p.rect.bottom <= 706)
        assertTrue(p.anchor in 0.1f..0.9f)
    }
    @Test fun `crowded bubble prioritizes todays card`() {
        val e = PetEnvironment(listOf(PetObstacle(Rect(0f, 0f, 150f, 500f), 14f), PetObstacle(Rect(150f, 0f, 350f, 500f), 2f)))
        val p = PetSpace.bubble(Rect(100f, 400f, 164f, 464f), Size(180f, 100f), 350f, 500f, 0f, e)
        assertTrue(p.rect.left >= 150)
    }
    @Test fun `entry greeting defaults and interval boundaries`() {
        assertEquals(PetWelcomePolicy.EVERY_ENTRY, PetPreferences().welcomePolicy)
        assertTrue(PetWelcomePolicy.EVERY_ENTRY.shouldWelcome(100, 100))
        assertFalse(PetWelcomePolicy.ONE_MINUTE.shouldWelcome(60_999, 1000))
        assertTrue(PetWelcomePolicy.ONE_MINUTE.shouldWelcome(61_000, 1000))
        assertTrue(PetWelcomePolicy.THIRTY_MINUTES.shouldWelcome(10, 0))
    }
    @Test fun `disabled greetings and backwards clock are handled`() {
        assertFalse(PetWelcomePolicy.OFF.shouldWelcome(Long.MAX_VALUE, 0))
        assertTrue(PetWelcomePolicy.FIVE_MINUTES.shouldWelcome(100, 500))
    }
    @Test fun `long absence and first visit have their own copy`() {
        val first = PetWelcomeLines.message("小贝", PetViewModel.Welcome(1, 0, true), 8)
        val away = PetWelcomeLines.message("小贝", PetViewModel.Welcome(2, 86_400_000, false), 8)
        assertTrue(first.text.contains("初次站岗")); assertTrue(away.text.startsWith("小贝，"))
        assertNotEquals(first.text, away.text)
        assertEquals(PetMessageKind.CASUAL, away.kind)
    }
}
