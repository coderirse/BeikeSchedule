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

object PetWelcomeLines {
    fun message(address: String, visit: PetViewModel.Welcome, hour: Int): PetMessage {
        val pool = when {
            visit.firstVisit -> listOf("初次站岗！我是你的课表小搭子，点点我就能说话，拖一拖还能换位置哦～")
            visit.absenceMillis >= 86_400_000 -> listOf(
                "好久不见呀！我的小板凳都坐热啦，快来击个掌～", "你回来啦！攒了一口袋的加油，先送你一大把～",
                "终于等到你～今天也一起把日子过得闪闪亮亮吧！", "欢迎回来！课表小搭子重新上岗，元气补给到位～")
            visit.absenceMillis >= 1_800_000 -> listOf(
                "回来啦！我刚在课表边上伸了个懒腰，位置给你留着呢～", "叮咚，你的小搭子上线啦！先看看安排，再慢慢出发～",
                "等到你啦～外面的事情辛苦了，送你一个小小的击掌！", "欢迎回来！把肩膀放松一点，咱们重新出发呀～")
            hour in 5..10 -> listOf("早呀！今天的元气我先帮你装满～", "早上好，书包和好心情准备出发了吗？", "一日之计在于……先给你加个油！")
            hour in 11..13 -> listOf("午间小搭子报到～忙归忙，记得好好吃饭呀！", "中午好！让眼睛和脑袋也歇一小会儿吧～", "午后的好运气，已经放进你的小书包啦！")
            hour in 14..18 -> listOf("下午好呀！给今天的后半场加点糖～", "我在课表边上坐好啦，陪你一点点往前走～", "欢迎回来！今天的小进步也值得一朵小红花～")
            else -> listOf("回来啦～忙了一天，也给自己留一点休息时间哦。", "晚间陪伴模式启动！我会轻轻地给你加油～", "夜色温柔，今天的努力也很闪亮呀～")
        }
        val prefix = address.takeIf(String::isNotBlank)?.let { "$it，" }.orEmpty()
        return PetMessage("welcome:${visit.id}", prefix + pool.random())
    }
}

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
        "课表是地图，努力是小脚印，今天也留下一个吧～", "我在空白处歇歇脚，你安心看安排就好呀。",
        "脑袋打结了吗？站起来走两步，给思路松松绑～", "不必每次都满分，敢再试一次就很棒啦！",
        "今日能量补给：一个微笑，再加一个小小的开始～", "把‘做不完’先放一边，咱们先做五分钟！",
        "一页笔记、一杯温水，今天也有稳稳的小幸福～", "刚刚偷偷练习了加油舞，现在表演给你看！",
        "小小的我，大大的应援，今天也站在你这边～", "计划偶尔拐个弯也没关系，我们可以调整路线呀。",
        "今天不想跑，就走一走；走不动了，就歇一歇。", "把难题圈起来，逐个击破，我在旁边举小旗～",
        "天气如何我还不知道，但可以先给你一份晴朗心情！", "我的口袋没有答案，但是装满了给你的鼓励～",
        "听说专注五分钟，也会长出一点点成就感哦！", "别让一个小失误，偷走你一整天的好心情呀。",
        "我会趴在边边守着，给你的课程让出大舞台～", "用完的水杯要续杯，努力的你也要补补能量！",
        "整理一下桌面，说不定思路也跟着排好队啦～", "复杂的事画个小图，线索可能就冒出来了！",
        "今天找到的新知识，明天再见一面就更熟啦～", "遇到不会的地方，提问也是很勇敢的一步呀。",
        "嘿咻！给你的待办清单吹一口好运气～", "一次只开一个小任务，脑袋就不用挤成早高峰啦。",
        "纸上的小小进度条，每一格都算数哦～", "困了就眨眨眼，累了就伸伸腰，小搭子在线提醒！",
        "你负责探索知识，我负责在边上蹦出加油～", "今天的困难先存档，休息好了还能接着挑战！",
        "偶尔卡住不是退步，是在给新本领加载呀～", "奖励自己一个小休息，快乐也要按时签到！",
        "现在的每一小步，都在给未来的自己铺路～", "把手机稍稍放低，肩膀放松，我也跟着伸个懒腰。",
        "一口吃不成胖鱼，一点点学就会慢慢变厉害！", "给今天起个可爱的标题吧：努力一点点，快乐多一点～",
        "课本翻开的一小声，就是今天出发的信号呀！", "完成之后记得夸夸自己，我先替你鼓个掌～",
        "今天也请收下大肥鱼牌的小小祝福：顺顺利利～", "难题像打结的毛线，耐心找找，总有一个线头。",
        "我正在角落认真值班，元气不足随时点我呀～", "慢慢来，先把眼前这一小件事做好就很棒啦！",
    )
    override suspend fun next(request: PetDialogueRequest): PetMessage {
        val candidates = lines.indices.filter { "line:$it" !in request.recentIds }.ifEmpty { lines.indices.toList() }
        val index = candidates[random.nextInt(candidates.size)]
        val prefix = request.address.takeIf(String::isNotBlank)?.let { "$it，" }.orEmpty()
        return PetMessage("line:$index", prefix + lines[index])
    }
}
