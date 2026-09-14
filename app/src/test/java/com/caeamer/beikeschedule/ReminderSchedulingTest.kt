package com.caeamer.beikeschedule

import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.pref.AlarmCodec
import com.caeamer.beikeschedule.data.pref.ScheduledAlarm
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import com.caeamer.beikeschedule.reminder.ReminderAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 上课/考试提醒排期的回归单测（v1.1.10）。
 *
 * 重点是「已到点但系统还没投递的闹钟绝不能被取消」—— 这是"偶发不提醒"的根因：
 * AlarmManager 在 Doze 下把投递推迟到维护窗口，而每日脉冲（非精确、同样会被推迟）和打开 App
 * 都可能恰好在这个窗口里触发一次重排；旧实现无条件取消全部再只重排"仍在未来"的，这条就没了。
 */
class ReminderSchedulingTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun course(
        id: Long,
        day: Int,
        startSection: Int,
        weeks: Set<Int> = (1..20).toSet(),
        name: String = "课程$id",
        hidden: Boolean = false,
        unscheduled: Boolean = false,
    ) = CourseEntity(
        id = id, taskId = "", name = name, teacher = "", location = "机械楼720",
        dayOfWeek = if (unscheduled) 0 else day,
        startSection = if (unscheduled) 0 else startSection,
        endSection = if (unscheduled) 0 else startSection + 1,
        weekBitmap = "0" + (1..20).joinToString("") { if (it in weeks) "1" else "0" },
        colorIndex = 0, source = CourseEntity.SOURCE_IMPORT, hidden = hidden,
    )

    /** 2026-09-07 是第 1 周周一。 */
    private val firstMonday = "2026-09-07"
    private val semester = SettingsStore.SemesterConfig(
        xn = "2026-2027", xq = "1", name = "2026-2027-1", firstMonday = firstMonday, totalWeeks = 20,
    )
    /** 只含第 1 周：8 天窗口里同一门周一课只会命中一次，断言更聚焦。 */
    private val oneWeekSemester = semester.copy(totalWeeks = 1)
    private val sectionStarts = mapOf(
        1 to "08:00", 3 to "09:55", 5 to "13:30", 7 to "15:20", 9 to "17:10", 11 to "19:30",
    )

    // ——— 核心：只取消仍在未来的闹钟 ———

    @Test
    fun `已到点的闹钟不能被取消`() {
        val now = millis(2026, 9, 7, 8, 0)
        val recorded = listOf(
            ScheduledAlarm(1, now - 60_000),   // 已到点，系统可能还没投递
            ScheduledAlarm(2, now + 60_000),   // 仍在未来
            ScheduledAlarm(3, now),            // 正好到点
        )
        val toCancel = ReminderAlarmScheduler.alarmsToCancel(recorded, plannedCodes = emptySet(), nowMillis = now)
        assertEquals(listOf(2), toCancel.map { it.requestCode })
    }

    @Test
    fun `未来闹钟即使本轮不再需要也要取消`() {
        val now = millis(2026, 9, 7, 8, 0)
        val recorded = listOf(ScheduledAlarm(7, now + 3_600_000))
        val toCancel = ReminderAlarmScheduler.alarmsToCancel(recorded, plannedCodes = emptySet(), nowMillis = now)
        assertEquals(listOf(7), toCancel.map { it.requestCode })
    }

    @Test
    fun `旧格式记录_仍在本轮计划里就不取消`() {
        // 升级前只存了 requestCode（没有时刻）。它可能正是"已到点未投递"的那条，
        // 而且紧接着会被重新设置覆盖，所以保守留着。
        val recorded = listOf(ScheduledAlarm(11, null), ScheduledAlarm(12, null))
        val toCancel = ReminderAlarmScheduler.alarmsToCancel(
            recorded, plannedCodes = setOf(11), nowMillis = millis(2026, 9, 7, 8, 0),
        )
        assertEquals(listOf(12), toCancel.map { it.requestCode })
    }

    @Test
    fun `用户主动关闭提醒时_连已到点的一起取消`() {
        // 关提醒后"再弹最后一次"才是 bug；这与"已到点不动它"并不矛盾：
        // 前者是用户明确要求别提醒，后者是避免把系统还没投递的提醒误删。
        val now = millis(2026, 9, 7, 8, 0)
        val recorded = listOf(ScheduledAlarm(1, now - 60_000), ScheduledAlarm(2, now + 60_000))
        val toCancel = ReminderAlarmScheduler.alarmsToCancel(
            recorded, plannedCodes = emptySet(), nowMillis = now, cancelDueAlarms = true,
        )
        assertEquals(setOf(1, 2), toCancel.map { it.requestCode }.toSet())
    }

    @Test
    fun `Doze 场景_清晨脉冲补跑时不清掉当天早八的提醒`() {
        // 复现原始 bug：早八课，7:45 该提醒；Doze 把 04:30 的脉冲和 7:45 的提醒一起推到 7:50 投递，
        // 脉冲先跑 → 重排。7:45 那条已到点，必须留在原位等系统投递。
        val now = LocalDateTime.of(2026, 9, 7, 7, 50)
        val seven45 = millis(2026, 9, 7, 7, 45)
        val mondayCourse = course(id = 1, day = 1, startSection = 1) // 周一 第1-2节 08:00
        val plan = ClassReminderScheduler.planClassReminders(
            courses = listOf(mondayCourse),
            sectionStartTimes = sectionStarts,
            semester = oneWeekSemester,
            minutes = 15,
            now = now,
            zone = zone,
        )
        // 7:45 已过 → 本轮不再排它（plan 为空），所以它不在 plannedCodes 里，
        // 旧实现会把它当"不再需要"直接取消 —— 这正是丢提醒的瞬间
        assertTrue("已过触发点不该重排", plan.isEmpty())

        val recorded = listOf(
            ScheduledAlarm(ClassReminderScheduler.requestCodeOf(mondayCourse, LocalDate.of(2026, 9, 7)), seven45),
        )
        val toCancel = ReminderAlarmScheduler.alarmsToCancel(
            recorded,
            plannedCodes = plan.map { it.requestCode }.toSet(),
            nowMillis = millis(2026, 9, 7, 7, 50),
        )
        assertTrue("已到点的 7:45 提醒被取消了，这条提醒会永久丢失", toCancel.isEmpty())
    }

    // ——— 排期计算 ———

    @Test
    fun `按节次开始时间与提前分钟数算出触发时刻`() {
        val plan = ClassReminderScheduler.planClassReminders(
            courses = listOf(course(id = 1, day = 1, startSection = 1)),
            sectionStartTimes = sectionStarts,
            semester = oneWeekSemester,
            minutes = 15,
            now = LocalDateTime.of(2026, 9, 7, 7, 0),
            zone = zone,
        )
        assertEquals(1, plan.size)
        assertEquals(millis(2026, 9, 7, 7, 45), plan[0].triggerAtMillis)
        assertEquals("课程1", plan[0].courseName)
        assertEquals("08:00", plan[0].startTime)
        assertEquals(15, plan[0].minutes)
    }

    @Test
    fun `已经过去的触发点不排`() {
        val plan = ClassReminderScheduler.planClassReminders(
            courses = listOf(course(id = 1, day = 1, startSection = 1)),
            sectionStartTimes = sectionStarts,
            semester = semester,
            minutes = 15,
            now = LocalDateTime.of(2026, 9, 7, 7, 50), // 已过 7:45
            zone = zone,
        )
        assertTrue("已过去的触发点不应补排", plan.none { it.triggerAtMillis == millis(2026, 9, 7, 7, 45) })
    }

    @Test
    fun `只排未来 8 天窗口`() {
        // 周一到周日每天一门课，从周一开始看 8 天窗口
        val courses = (1..7).map { course(id = it.toLong(), day = it, startSection = 1) }
        val plan = ClassReminderScheduler.planClassReminders(
            courses = courses,
            sectionStartTimes = sectionStarts,
            semester = semester,
            minutes = 15,
            now = LocalDateTime.of(2026, 9, 7, 0, 0),
            zone = zone,
        )
        val days = plan.map { it.triggerAtMillis }.distinct().size
        assertEquals("8 天窗口 = 今天 + 之后 7 天", 8, days)
    }

    @Test
    fun `该周没课的课程不排`() {
        // 只有第 5 周有课，今天第 1 周
        val plan = ClassReminderScheduler.planClassReminders(
            courses = listOf(course(id = 1, day = 1, startSection = 1, weeks = setOf(5))),
            sectionStartTimes = sectionStarts,
            semester = semester,
            minutes = 15,
            now = LocalDateTime.of(2026, 9, 7, 0, 0),
            zone = zone,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `开学前不排当天的课_但学期内的课照排`() {
        // now = 8/31（开学前一周），8 天窗口会覆盖到 9/7 开学当天
        val plan = ClassReminderScheduler.planClassReminders(
            courses = listOf(course(id = 1, day = 1, startSection = 1)),
            sectionStartTimes = sectionStarts,
            semester = semester,
            minutes = 15,
            now = LocalDateTime.of(2026, 8, 31, 0, 0),
            zone = zone,
        )
        val semesterStart = millis(2026, 9, 7, 0, 0)
        assertTrue("开学前的日期不该排出任何提醒", plan.all { it.triggerAtMillis >= semesterStart })
        assertEquals("9/7 开学当天那节应排上", millis(2026, 9, 7, 7, 45), plan.single().triggerAtMillis)
    }

    @Test
    fun `节次时间缺失只跳过该课_不影响其他课`() {
        val plan = ClassReminderScheduler.planClassReminders(
            courses = listOf(
                course(id = 1, day = 1, startSection = 3), // 节次表里没有 3 这一条
                course(id = 2, day = 1, startSection = 1),
            ),
            sectionStartTimes = mapOf(1 to "08:00"),
            semester = oneWeekSemester,
            minutes = 15,
            now = LocalDateTime.of(2026, 9, 7, 0, 0),
            zone = zone,
        )
        assertEquals(listOf("课程2"), plan.map { it.courseName })
    }

    @Test
    fun `requestCode 内容寻址_稳定且落在上课提醒区间`() {
        val c = course(id = 42, day = 1, startSection = 1)
        val d = LocalDate.of(2026, 9, 7)
        val a = ClassReminderScheduler.requestCodeOf(c, d)
        val b = ClassReminderScheduler.requestCodeOf(c, d)
        assertEquals("同一(课程,日期)必须稳定", a, b)
        assertTrue("必须落在 [0, 8e6)，与考试提醒/每日脉冲隔离", a in 0 until 8_000_000)
    }

    // ——— 考试提醒 ———

    @Test
    fun `考试提醒排考前一天20点与开考前1小时`() {
        val exam = ExamEntity(
            id = 1, kcdm = "1060122", kcmc = "概率论与数理统计A", kslx = "期末考试",
            kssjms = "2027-01-15 08:00~09:50", ksrq = "2027-01-15", kssj = "08:00", jssj = "09:50",
            cdmc = "机械楼114", zwh = "12", jkjsbz = "", kkyxmc = "数理学院", xnxq = "2026-20271",
        )
        val plan = ExamReminderScheduler.planExamReminders(
            exams = listOf(exam),
            // 30 天窗口内才有提醒，所以"当前时间"取考前 5 天
            now = LocalDateTime.of(2027, 1, 10, 0, 0),
            zone = zone,
        )
        assertEquals(2, plan.size)
        assertEquals(millis(2027, 1, 14, 20, 0), plan[0].triggerAtMillis)
        assertEquals("明天考试", plan[0].titlePrefix)
        assertEquals(millis(2027, 1, 15, 7, 0), plan[1].triggerAtMillis)
        assertEquals("即将考试", plan[1].titlePrefix)
        assertTrue("考试提醒的码必须在 8e6 段", plan.all { it.requestCode >= 8_000_000 })
    }

    @Test
    fun `无开始时间的考试只排前一天`() {
        val exam = ExamEntity(
            id = 2, kcdm = "x", kcmc = "大学物理B", kslx = "期末考试",
            kssjms = "第16周 星期五", ksrq = "", kssj = "", jssj = "",
            cdmc = "", zwh = "", jkjsbz = "", kkyxmc = "", xnxq = "2026-20271",
        )
        assertTrue(ExamReminderScheduler.planExamReminders(listOf(exam), LocalDateTime.of(2026, 9, 7, 0, 0), zone).isEmpty())
    }

    // ——— 记录编解码（含旧格式兼容） ———

    @Test
    fun `记录编解码往返`() {
        val alarms = listOf(ScheduledAlarm(1, 100L), ScheduledAlarm(-5, 200L))
        assertEquals(alarms, AlarmCodec.decode(AlarmCodec.encode(alarms)))
    }

    @Test
    fun `旧格式记录解析为无触发时刻`() {
        val decoded = AlarmCodec.decode("11,12,13")
        assertEquals(3, decoded.size)
        assertTrue(decoded.all { it.triggerAtMillis == null })
        assertEquals(listOf(11, 12, 13), decoded.map { it.requestCode })
    }

    @Test
    fun `空与非法记录安全解析`() {
        assertTrue(AlarmCodec.decode(null).isEmpty())
        assertTrue(AlarmCodec.decode("").isEmpty())
        assertTrue(AlarmCodec.decode("  ").isEmpty())
        assertEquals(listOf(ScheduledAlarm(7, null)), AlarmCodec.decode("abc,7,,"))
    }

    @Test
    fun `编码后的记录带触发时刻`() {
        val encoded = AlarmCodec.encode(listOf(ScheduledAlarm(3, 1234L)))
        assertEquals("3:1234", encoded)
        assertNotNull(AlarmCodec.decode(encoded).single().triggerAtMillis)
        assertFalse(AlarmCodec.decode(encoded).single().triggerAtMillis == null)
    }
}
