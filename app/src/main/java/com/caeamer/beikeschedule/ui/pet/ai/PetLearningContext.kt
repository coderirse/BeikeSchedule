package com.caeamer.beikeschedule.ui.pet.ai

import com.caeamer.beikeschedule.data.local.*
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.*
import com.caeamer.beikeschedule.import.parser.CreditProgressParser
import com.caeamer.beikeschedule.model.CourseVisibility
import com.caeamer.beikeschedule.model.TodoPlanner
import com.caeamer.beikeschedule.model.WeekResolver
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalDateTime

/** 工具只输出允许的业务字段，绝不序列化 Repository、SettingsStore 或云快照。 */
class PetLearningContext(private val repo: ScheduleRepository) {
    suspend fun read(scopes: Set<LearningScope>, from: LocalDate, days: Int): JsonObject = buildJsonObject {
        put("asOf", LocalDateTime.now().toString()); put("rangeStart", from.toString()); put("days", days.coerceIn(1, 31))
        val dates = (0 until days.coerceIn(1, 31)).map { from.plusDays(it.toLong()) }
        if (LearningScope.COURSES in scopes) {
            put("courses", courseData(repo.courses.first(), repo.sectionTimes.first(), repo.settings.semester.first(), dates))
        }
        if (LearningScope.EXAMS in scopes) {
            put("examsFetchedAtMillis", repo.settings.examsFetchedAt.first())
            put("exams", JsonArray(repo.exams.first().filter { exam ->
                exam.ksrq.isBlank() || runCatching { LocalDate.parse(exam.ksrq) in dates }.getOrDefault(false)
            }.take(100).map { exam -> buildJsonObject {
                put("name", exam.kcmc); put("date", exam.ksrq); put("start", exam.kssj); put("end", exam.jssj)
                put("timeDescription", exam.kssjms); put("location", exam.cdmc); put("seat", exam.zwh); put("note", exam.jkjsbz)
            } }))
        }
        if (LearningScope.TODOS in scopes) {
            val todos = repo.todos.first()
            put("todos", JsonArray(dates.flatMap { date -> todos.filter { TodoPlanner.occursOn(it, date) }.map { todo ->
                buildJsonObject { put("date", date.toString()); put("title", todo.title); put("time", todo.time); put("note", todo.note)
                    put("done", TodoPlanner.isDoneOn(todo, date, LocalDate.now())) }
            } }.take(300)))
            put("overdueOnce", JsonArray(todos.filter { it.repeatMode == TodoEntity.REPEAT_ONCE && it.lastDoneDate.isBlank() && it.date.isNotBlank() && it.date < LocalDate.now().toString() }
                .take(60).map { buildJsonObject { put("title", it.title); put("date", it.date); put("time", it.time) } }))
        }
        if (LearningScope.GRADES in scopes) {
            put("gradesFetchedAtMillis", repo.settings.gradesFetchedAt.first())
            put("statisticsScope", "全部已导入学期；当前本机缓存，非本次向学校实时刷新")
            val rows = repo.grades.first()
            val selected = GradeRows.bestPerCourse(rows)
            val excluded = repo.settings.weightedExcludedKcdm.first()
            val weighted = WeightedScoreCalculator.calculate(selected.map { WeightedScoreCalculator.GradeTriple(it.identityKey, it.xf, it.numericScore, it.kcxz) }, excluded)
            put("grades", JsonArray(rows.take(350).map { grade -> buildJsonObject {
                put("name", grade.kcmc); put("semester", grade.xnxqmc); put("score", grade.zzcj); put("credits", grade.xf)
                put("nature", grade.kcxz); put("retake", grade.bkcx); put("rank", grade.pm); put("students", grade.zrs)
            } }))
            GpaCalculator.calculate(rows)?.let { gpa -> put("gpa", buildJsonObject { put("value", gpa.gpa); put("credits", gpa.credits); put("courses", gpa.courseCount) }) }
            weighted?.let { put("requiredWeighted", it.score) }
            put("statisticsPolicy", "GPA 4.0 制，数字成绩按课程及补考/重修收敛；加权只计必修，沿用用户排除设置。不自行改变统计定义。")
            if (rows.size > 350) put("gradesTruncated", true)
        }
        if (LearningScope.CREDITS in scopes) {
            val sums = CreditAggregator.sumPassedByCategory(repo.grades.first())
            val categories = CreditProgressParser.parseCategories(repo.settings.xflbyqJson.first())
            put("creditCategories", JsonArray(categories.map { row -> buildJsonObject {
                put("category", row.kclbmc); put("nature", row.kcxzmc); put("required", row.yqxf)
                put("completed", CreditAggregator.completedCreditsFor(sums, row.kclbmc))
            } }))
            CreditProgressParser.parseProgress(repo.settings.bxkqkJson.first())?.let { progress -> put("graduation", buildJsonObject {
                put("required", progress.yqxf); put("completed", progress.ywcxf); put("remaining", progress.wwcxf)
                put("passedCourses", progress.ywcms); put("remainingCourses", progress.wwcms)
            }) }
        }
        if (LearningScope.PROFILE in scopes) {
            val p = repo.settings.studentProfile.first()
            put("profile", buildJsonObject { put("name", p.xm); put("college", p.yxmc); put("major", p.zymc); put("class", p.bjmc); put("year", p.njmc) })
        }
    }

    companion object {
        fun requestedScopes(args: JsonObject, allowed: Set<LearningScope>): Set<LearningScope> =
            (args["scopes"] as? JsonArray).orEmpty().mapNotNull { raw ->
                LearningScope.entries.firstOrNull { it.name == (raw as? JsonPrimitive)?.contentOrNull }
            }.toSet().intersect(allowed)

        fun courseData(courses: List<CourseEntity>, sections: List<SectionTimeEntity>, semester: SettingsStore.SemesterConfig, dates: List<LocalDate>): JsonArray =
            JsonArray(dates.take(31).map { date ->
                val day = WeekResolver.daySchedule(semester, date)
                val visible = CourseVisibility.onDate(courses, date).filter { course ->
                    !day.holiday && day.week != null && course.dayOfWeek == day.coursesDayOfWeek && course.hasClassOnWeek(day.week)
                }
                buildJsonObject {
                    put("date", date.toString()); put("teachingWeek", day.week?.let(::JsonPrimitive) ?: JsonNull)
                    put("effectiveWeekday", day.coursesDayOfWeek); put("holiday", day.holiday); put("makeup", day.makeup)
                    put("courses", JsonArray(visible.take(40).map { course -> buildJsonObject {
                        put("name", course.name); put("location", course.location); put("teacher", course.teacher)
                        put("startSection", course.startSection); put("endSection", course.endSection)
                        put("start", sections.firstOrNull { it.section == course.startSection }?.startTime.orEmpty())
                        put("end", sections.firstOrNull { it.section == course.endSection }?.endTime.orEmpty())
                    } }))
                }
            })
    }
}
