package com.caeamer.beikeschedule.ui.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.CourseVisibility
import com.caeamer.beikeschedule.model.SectionMap
import com.caeamer.beikeschedule.model.WeekLayout
import com.caeamer.beikeschedule.model.WeekResolver
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

data class PetObstacle(val rect: Rect, val weight: Float, val perchable: Boolean = false)
data class PetEnvironment(val cards: List<PetObstacle> = emptyList(), val headerBottom: Float = 0f, val sectionWidth: Float = 0f)
enum class PetPosture { STANDING, PERCH, LEAN_LEFT, LEAN_RIGHT }
data class PetStop(val position: Offset, val posture: PetPosture = PetPosture.STANDING)
enum class PetTailSide { TOP, BOTTOM, LEFT, RIGHT }
data class PetBubblePlacement(val rect: Rect, val side: PetTailSide, val anchor: Float)

/** 共用渲染模型，按实测 Pager 大小推算矩形；不向核心课程/日历写入任何字段。 */
fun petEnvironment(
    courses: List<CourseEntity>, semester: SettingsStore.SemesterConfig, week: Int, days: List<Int>,
    hideInactive: Boolean, grid: Rect, sectionWidth: Float, clearance: Float, inset: Float,
    sections: List<SectionTimeEntity>, now: LocalDateTime,
): PetEnvironment {
    if (grid.width <= sectionWidth || grid.height <= clearance || days.isEmpty()) return PetEnvironment()
    val monday = WeekResolver.weekMonday(semester, week) ?: return PetEnvironment(headerBottom = grid.top, sectionWidth = sectionWidth)
    val columnWidth = (grid.width - sectionWidth) / days.size
    val unit = (grid.height - clearance) / SectionMap.TOTAL_SMALL_SECTIONS
    val cards = mutableListOf<PetObstacle>()
    days.forEachIndexed { column, day ->
        val date = monday.plusDays((day - 1).toLong())
        val plan = WeekResolver.daySchedule(semester, date)
        val layout = WeekLayout.layoutDay(CourseVisibility.onDate(courses, date), day, week, hideInactive,
            coursesDayOfWeek = plan.coursesDayOfWeek, holiday = plan.holiday)
        fun add(course: CourseEntity, lane: Int, lanes: Int, active: Boolean) {
            val start = course.startSection.coerceAtMost(SectionMap.TOTAL_SMALL_SECTIONS)
            val end = course.endSection.coerceAtMost(SectionMap.TOTAL_SMALL_SECTIONS)
            val left = grid.left + sectionWidth + column * columnWidth + lane * columnWidth / lanes
            val rect = Rect(left + inset, grid.top + (start - 1) * unit + inset,
                left + columnWidth / lanes - inset,
                grid.top + end * unit - inset)
            val today = date == now.toLocalDate()
            val time = sections.firstOrNull { it.section == start }?.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val near = today && time != null && time >= now.toLocalTime().minusMinutes(90) && time <= now.toLocalTime().plusHours(2)
            cards += PetObstacle(rect, if (near && active) 14f else if (today && active) 8f else if (active) 2f else 1f, today && active)
        }
        layout.clusters.forEach { cluster -> cluster.forEachIndexed { lane, course -> add(course, lane, cluster.size, true) } }
        layout.inactives.forEach { add(it, 0, 1, false) }
    }
    return PetEnvironment(cards, grid.top, sectionWidth)
}

internal object PetSpace {
    private fun overlap(a: Rect, b: Rect): Float =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
    fun body(stop: PetStop, size: Float): Rect {
        val h = if (stop.posture == PetPosture.PERCH) size * 0.7f else size
        return Rect(stop.position.x, stop.position.y + size - h, stop.position.x + size, stop.position.y + size)
    }
    fun protected(width: Float, height: Float, clearance: Float, env: PetEnvironment): List<Rect> = listOf(
        Rect(0f, 0f, width, env.headerBottom), Rect(0f, 0f, env.sectionWidth, height),
        Rect((width - clearance).coerceAtLeast(0f), (height - clearance).coerceAtLeast(0f), width, height))
    fun free(point: Offset, width: Float, height: Float, size: Float, clearance: Float, env: PetEnvironment, posture: PetPosture = PetPosture.STANDING): Boolean {
        if (point.x < 0 || point.y < 0 || point.x + size > width + 0.5f || point.y + size > height + 0.5f) return false
        val rect = body(PetStop(point, posture), size)
        return (env.cards.map { it.rect } + protected(width, height, clearance, env)).none { overlap(rect, it) > 0.5f }
    }
    fun score(rect: Rect, env: PetEnvironment): Float = env.cards.sumOf { (overlap(rect, it.rect) * it.weight).toDouble() }.toFloat()
    fun destinations(width: Float, height: Float, size: Float, clearance: Float, env: PetEnvironment): List<PetStop> {
        val maxX = (width - size).coerceAtLeast(0f); val maxY = (height - size).coerceAtLeast(0f)
        val candidates = mutableListOf<PetStop>()
        for (x in 0..12) for (y in 0..18) candidates += PetStop(Offset(maxX * x / 12, maxY * y / 18))
        for (x in 0..12) candidates += PetStop(Offset(maxX * x / 12, maxY), PetPosture.PERCH)
        for (y in 4..14) {
            candidates += PetStop(Offset(0f, maxY * y / 18), PetPosture.LEAN_LEFT)
            candidates += PetStop(Offset(maxX, maxY * y / 18), PetPosture.LEAN_RIGHT)
        }
        env.cards.filter { it.perchable }.forEach { card ->
            candidates += PetStop(Offset((card.rect.center.x - size / 2).coerceIn(0f, maxX), card.rect.top - size), PetPosture.PERCH)
        }
        return candidates.filter { free(it.position, width, height, size, clearance, env, it.posture) }
    }
    fun choose(width: Float, height: Float, size: Float, clearance: Float, env: PetEnvironment, random: Random = Random.Default): PetStop {
        val all = destinations(width, height, size, clearance, env)
        val rest = all.filter { it.posture != PetPosture.STANDING }
        val courseStops = rest.filter { stop -> env.cards.any { it.perchable && abs(stop.position.y + size - it.rect.top) < 1f } }
        val pool = when {
            courseStops.isNotEmpty() && random.nextInt(4) == 0 -> courseStops
            rest.isNotEmpty() && random.nextInt(3) == 0 -> rest
            else -> all
        }
        if (pool.isEmpty()) return PetStop(safePetPosition(Offset(env.sectionWidth, height - size), width, height, size, clearance), PetPosture.PERCH)
        val today = env.cards.filter { it.weight >= 8 }.map { it.rect.center }
        val ranked = pool.sortedBy { stop ->
            today.sumOf { (1f / ((stop.position - it).getDistance() / size + 1f)).toDouble() }
        }
        return ranked.take(8).random(random)
    }
    fun clearSegment(a: Offset, b: Offset, width: Float, height: Float, size: Float, clearance: Float, env: PetEnvironment): Boolean {
        val steps = ceil((b - a).getDistance() / (size / 4).coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        return (1..steps).all { i -> free(a + (b - a) * (i.toFloat() / steps), width, height, size, clearance, env) }
    }
    /** 有界四邻域搜索，不能只保证终点安全而直线穿过课程。无可达路径时留在原位。 */
    fun route(start: Offset, target: Offset, width: Float, height: Float, size: Float, clearance: Float, env: PetEnvironment): List<Offset> {
        if (clearSegment(start, target, width, height, size, clearance, env)) return listOf(target)
        val points = (0..18).flatMap { x -> (0..24).map { y -> Offset((width - size).coerceAtLeast(0f) * x / 18, (height - size).coerceAtLeast(0f) * y / 24) } }
        val valid = points.indices.filter { free(points[it], width, height, size, clearance, env) }.toSet()
        // 用户可手动拖到课程上，此时先就近退出已有重叠；正常自动路线仍完整避障。
        val escaping = !free(start, width, height, size, clearance, env)
        val first = valid.filter { escaping || clearSegment(start, points[it], width, height, size, clearance, env) }
            .minByOrNull { (points[it] - start).getDistance() } ?: return emptyList()
        val queue = ArrayDeque<Int>(); queue += first
        val previous = mutableMapOf(first to -1)
        var goal: Int? = null
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (clearSegment(points[p], target, width, height, size, clearance, env)) { goal = p; break }
            for (n in listOf(p - 25, p + 25, p - 1, p + 1)) {
                if (n !in valid || n in previous || abs(n / 25 - p / 25) + abs(n % 25 - p % 25) != 1) continue
                if (!clearSegment(points[p], points[n], width, height, size, clearance, env)) continue
                previous[n] = p; queue += n
            }
        }
        var p = goal ?: return emptyList()
        val path = mutableListOf(target)
        while (p >= 0) { path += points[p]; p = previous[p] ?: -1 }
        return path.asReversed()
    }
    fun bubble(pet: Rect, size: Size, width: Float, height: Float, clearance: Float, env: PetEnvironment): PetBubblePlacement {
        val w = size.width.coerceAtMost(width); val h = size.height.coerceAtMost(height)
        val gap = 8f
        val points = mutableListOf(
            Offset(pet.center.x - w / 2, pet.top - h - gap), Offset(pet.center.x - w / 2, pet.bottom + gap),
            Offset(pet.left - w - gap, pet.center.y - h / 2), Offset(pet.right + gap, pet.center.y - h / 2))
        for (x in 0..6) for (y in 0..10) points += Offset((width - w) * x / 6, (height - h) * y / 10)
        val blocks = protected(width, height, clearance, env)
        val rect = points.map { Rect(Offset(it.x.coerceIn(0f, (width - w).coerceAtLeast(0f)), it.y.coerceIn(0f, (height - h).coerceAtLeast(0f))), Size(w, h)) }
            .minBy { r -> score(r, env) * 20 + blocks.sumOf { (overlap(r, it) * 1000).toDouble() }.toFloat() + overlap(r, pet) * 30 + (r.center - pet.center).getDistance() }
        val delta = pet.center - rect.center
        val side = if (abs(delta.x) / w > abs(delta.y) / h) { if (delta.x < 0) PetTailSide.LEFT else PetTailSide.RIGHT }
            else if (delta.y < 0) PetTailSide.TOP else PetTailSide.BOTTOM
        val anchor = if (side == PetTailSide.TOP || side == PetTailSide.BOTTOM) (pet.center.x - rect.left) / w else (pet.center.y - rect.top) / h
        return PetBubblePlacement(rect, side, anchor.coerceIn(0.1f, 0.9f))
    }
}
