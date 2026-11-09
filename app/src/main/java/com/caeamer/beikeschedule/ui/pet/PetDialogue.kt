package com.caeamer.beikeschedule.ui.pet

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.model.CourseMerger
import com.caeamer.beikeschedule.model.CourseVisibility
import com.caeamer.beikeschedule.model.NextClass
import com.caeamer.beikeschedule.model.WeekResolver
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

enum class PetMessageKind { COURSE, EXAM, CASUAL }
data class PetMessage(val id: String, val text: String, val kind: PetMessageKind = PetMessageKind.CASUAL, val priority: Int = 9)
data class PetFacts(
    val courses: List<CourseEntity>, val sections: List<SectionTimeEntity>,
    val semester: SettingsStore.SemesterConfig, val exams: List<ExamEntity>, val courseLeadMinutes: Int,
)

/** 与随机文案分开：准确的日期/课程规则不交给模型或文案随机数。只读，不调度系统提醒。 */
object PetHints {
    fun resolve(facts: PetFacts, now: LocalDateTime, prefs: PetPreferences): List<PetMessage> {
        val prefix = prefs.address.takeIf { it.isNotBlank() }?.let { "$it，" }.orEmpty()
        val messages = mutableListOf<PetMessage>()
        if (prefs.courseHints) {
            val plan = WeekResolver.daySchedule(facts.semester, now.toLocalDate())
            val target = NextClass.resolve(facts.courses, facts.sections.associate { it.section to it.startTime }, plan, now)
            val start = target?.let { runCatching { LocalTime.parse(it.startTime) }.getOrNull() }
            val course = target?.let { t -> CourseVisibility.onDate(facts.courses, now.toLocalDate()).firstOrNull { it.id == t.courseId } }
            if (course != null && start != null) {
                val seconds = Duration.between(now, LocalDateTime.of(now.toLocalDate(), start)).seconds
                if (seconds in 1..(facts.courseLeadMinutes.coerceAtLeast(1) * 60L)) {
                    val place = CourseMerger.stripCampusPrefix(course.location).takeIf { it.isNotBlank() && it != "-" }
                    messages += PetMessage("course:${now.toLocalDate()}:${course.name}:${course.startSection}",
                        "${prefix}还有 ${(seconds + 59) / 60} 分钟上《${course.name}》啦！" +
                            (place?.let { "去 $it，" } ?: "") + "带上书本，冲鸭～", PetMessageKind.COURSE, 1)
                }
            }
        }
        if (prefs.examHints) facts.exams.forEach { exam ->
            val date = runCatching { LocalDate.parse(exam.ksrq) }.getOrNull() ?: return@forEach
            if (date != now.toLocalDate() && date != now.toLocalDate().plusDays(1)) return@forEach
            val time = runCatching { LocalTime.parse(exam.kssj) }.getOrNull()
            val seconds = time?.let { Duration.between(now, LocalDateTime.of(date, it)).seconds }
            if (seconds != null && seconds <= 0) return@forEach
            val whenText = when {
                seconds != null && seconds <= 3600 -> "还有 ${(seconds + 59) / 60} 分钟"
                date == now.toLocalDate() -> "今天${time?.let { " $it" }.orEmpty()}"
                else -> "明天${time?.let { " $it" }.orEmpty()}"
            }
            val place = exam.cdmc.takeIf(String::isNotBlank)?.let { "地点 $it。" }.orEmpty()
            messages += PetMessage("exam:${exam.kcdm}:${exam.kcmc}:${exam.ksrq}:${exam.kssj}",
                "${prefix}${whenText}有《${exam.kcmc}》考试！$place" +
                    if (time == null) "开考时间记得再确认，我给你加油呀～" else "证件文具装好，我给你加油呀～",
                PetMessageKind.EXAM, if (seconds != null && seconds <= 3600) 0 else 2)
        }
        return messages.sortedBy { it.priority }
    }
}

data class PetDialogueRequest(val address: String, val recentIds: Set<String>)
/** 后续可另加远端提供者；首版只使用本地实现，不包含联网、密钥或用户课表上传。 */
fun interface PetDialogueProvider { suspend fun next(request: PetDialogueRequest): PetMessage }

class LocalPetDialogueProvider(private val random: Random = Random.Default) : PetDialogueProvider {
    private val lines = listOf(
        "今天也在悄悄变厉害呢，给你一朵小红花～", "困难先切成小块，一口一口就吃掉啦！",
        "我负责可爱，你负责一点点进步，合作愉快呀～", "伸个懒腰，喝口水，脑袋也要充充电！",
        "别急着和别人比速度，自己的节奏也很闪亮！", "学不进去的时候，先从最小的一步开始吧～",
        "认真生活的样子，已经很酷啦！", "今天的小目标，完成一个就值得拍拍手！",
        "悄悄告诉你：休息也是计划的一部分哦。", "知识可能还没进脑袋，但努力我都看见啦！",
        "背包检查一下：书本、钥匙，还有今天的好心情～", "允许自己慢一点，我们一起往前走。",
        "灵感还没来？先动动笔，说不定她就在路上啦！", "今天的你也很值得被鼓励，击个掌～",
        "大任务交给小步骤，紧张交给我来赶跑！", "路过你的课表，顺手送上一份好运气～",
        "眼睛离开屏幕看远一点，世界也在等你眨眨眼～", "新的一页，写一点点也算新的开始呀。",
        "学而时习之，不亦说乎？——《论语·学而》。今天也复习一点点吧～",
        "千里之行，始于足下。——《道德经·第六十四章》。先迈出今天的一小步～",
    )
    override suspend fun next(request: PetDialogueRequest): PetMessage {
        val candidates = lines.indices.filter { "line:$it" !in request.recentIds }.ifEmpty { lines.indices.toList() }
        val index = candidates[random.nextInt(candidates.size)]
        val prefix = request.address.takeIf(String::isNotBlank)?.let { "$it，" }.orEmpty()
        return PetMessage("line:$index", prefix + lines[index])
    }
}
