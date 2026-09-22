package com.caeamer.beikeschedule.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.caeamer.beikeschedule.data.local.AppDatabase
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.local.ExamEntity
import com.caeamer.beikeschedule.data.local.GradeEntity
import com.caeamer.beikeschedule.data.local.SectionTimeEntity
import com.caeamer.beikeschedule.data.local.TodoEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import com.caeamer.beikeschedule.reminder.ExamReminderScheduler
import com.caeamer.beikeschedule.reminder.TodoReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 云端整包快照（schemaVersion = 1）。
 *
 * **为什么不用 Room 实体直接序列化**：实体带自增主键 id，云端快照按"数据内容"存、
 * 本地按"整表覆盖"恢复，id 必须重新分配；且 DTO 与 Room schema 解耦后，
 * 快照格式可以独立演进（老快照在新版 App 上仍可恢复，靠 ignoreUnknownKeys + 默认值）。
 *
 * 排除项（有意不进快照）：
 * - 三张已排闹钟记录（REMINDER_CODES 等）：requestCode 是设备本地概念，恢复后由
 *   各 ReminderScheduler 按**本机**现有数据重排；
 * - 无课教室的楼栋/Tab 记忆：纯 UI 状态，无同步价值。
 */
@Serializable
data class CloudSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long = 0,
    val courses: List<CourseDto> = emptyList(),
    val sectionTimes: List<SectionTimeDto> = emptyList(),
    val grades: List<GradeDto> = emptyList(),
    val exams: List<ExamDto> = emptyList(),
    val todos: List<TodoEntity> = emptyList(), // TodoEntity 本就是 @Serializable，直接复用
    val settings: SettingsDto = SettingsDto(),
) {
    companion object {
        const val SCHEMA_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(snapshot: CloudSnapshot): String = json.encodeToString(serializer(), snapshot)

        /** 解码失败（含 schemaVersion 不认识）抛 [SerializationException]，由调用方转成用户可读文案。 */
        fun decode(text: String): CloudSnapshot {
            val snapshot = json.decodeFromString(serializer(), text)
            if (snapshot.schemaVersion > SCHEMA_VERSION) {
                throw SerializationException("云端备份由更新版本的 App 创建，请先升级本应用")
            }
            return snapshot
        }
    }
}

@Serializable
data class CourseDto(
    val taskId: String = "",
    val name: String = "",
    val teacher: String = "",
    val location: String = "",
    val dayOfWeek: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
    val weekBitmap: String = "",
    val colorIndex: Int = 0,
    val source: Int = CourseEntity.SOURCE_MANUAL,
    val hidden: Boolean = false,
)

@Serializable
data class SectionTimeDto(
    val section: Int,
    val startTime: String = "",
    val endTime: String = "",
)

@Serializable
data class GradeDto(
    val kcdm: String = "",
    val kcmc: String = "",
    val xnxq: String = "",
    val xnxqmc: String = "",
    val kcxz: String = "",
    val kclb: String = "",
    val xf: Double = 0.0,
    val zzcj: String = "",
    val bkcx: String = "",
    val yxmc: String = "",
    val sffx: Boolean = false,
    val pm: String = "",
    val zrs: String = "",
    val khfs: String = "",
)

@Serializable
data class ExamDto(
    val kcdm: String = "",
    val kcmc: String = "",
    val kslx: String = "",
    val kssjms: String = "",
    val ksrq: String = "",
    val kssj: String = "",
    val jssj: String = "",
    val cdmc: String = "",
    val zwh: String = "",
    val jkjsbz: String = "",
    val kkyxmc: String = "",
    val xnxq: String = "",
)

@Serializable
data class SettingsDto(
    val semester: SemesterDto = SemesterDto(),
    val reminderEnabled: Boolean = false,
    val reminderMinutes: Int = 15,
    val themeMode: String = "",
    val gpaJson: String = "",
    val gradesFetchedAt: Long = 0,
    val xflbyqJson: String = "",
    val bxkqkJson: String = "",
    val studentProfile: StudentProfileDto = StudentProfileDto(),
    val weightedSemester: String = "",
    val weightedExcluded: List<String> = emptyList(),
    val hideWeekend: Boolean = false,
    val hideInactiveCourses: Boolean = false,
)

@Serializable
data class SemesterDto(
    val xn: String = "",
    val xq: String = "",
    val name: String = "",
    val firstMonday: String = "",
    val totalWeeks: Int = 20,
    val weekMondays: List<String> = emptyList(),
)

@Serializable
data class StudentProfileDto(
    val xm: String = "",
    val xh: String = "",
    val yxmc: String = "",
    val zymc: String = "",
    val bjmc: String = "",
    val njmc: String = "",
    val xjsfzx: String = "",
    val xjsfzc: String = "",
)

/** 快照 ↔ 本地数据（Room 五表 + DataStore 配置）的双向转换。 */
object CloudSnapshotCodec {

    /** 读取本机全部数据组装快照（IO 挂起，调用方自行选调度器）。 */
    suspend fun build(context: Context): CloudSnapshot {
        val repo = ScheduleRepository(context)
        val settings = repo.settings
        val semester = settings.semester.first()
        val profile = settings.studentProfile.first()
        return CloudSnapshot(
            exportedAt = System.currentTimeMillis(),
            courses = repo.courses.first().map { it.toDto() },
            sectionTimes = repo.sectionTimes.first().map { SectionTimeDto(it.section, it.startTime, it.endTime) },
            grades = repo.grades.first().map {
                GradeDto(
                    kcdm = it.kcdm, kcmc = it.kcmc, xnxq = it.xnxq, xnxqmc = it.xnxqmc,
                    kcxz = it.kcxz, kclb = it.kclb, xf = it.xf, zzcj = it.zzcj, bkcx = it.bkcx,
                    yxmc = it.yxmc, sffx = it.sffx, pm = it.pm, zrs = it.zrs, khfs = it.khfs,
                )
            },
            exams = repo.exams.first().map {
                ExamDto(
                    kcdm = it.kcdm, kcmc = it.kcmc, kslx = it.kslx, kssjms = it.kssjms,
                    ksrq = it.ksrq, kssj = it.kssj, jssj = it.jssj, cdmc = it.cdmc,
                    zwh = it.zwh, jkjsbz = it.jkjsbz, kkyxmc = it.kkyxmc, xnxq = it.xnxq,
                )
            },
            todos = repo.todos.first(),
            settings = SettingsDto(
                semester = SemesterDto(
                    xn = semester.xn, xq = semester.xq, name = semester.name,
                    firstMonday = semester.firstMonday, totalWeeks = semester.totalWeeks,
                    weekMondays = semester.weekMondays,
                ),
                reminderEnabled = settings.reminderEnabled.first(),
                reminderMinutes = settings.reminderMinutes.first(),
                themeMode = settings.themeMode.first().name,
                gpaJson = settings.gpaCache.first(),
                gradesFetchedAt = settings.gradesFetchedAt.first(),
                xflbyqJson = settings.xflbyqJson.first(),
                bxkqkJson = settings.bxkqkJson.first(),
                studentProfile = StudentProfileDto(
                    xm = profile.xm, xh = profile.xh, yxmc = profile.yxmc, zymc = profile.zymc,
                    bjmc = profile.bjmc, njmc = profile.njmc, xjsfzx = profile.xjsfzx, xjsfzc = profile.xjsfzc,
                ),
                weightedSemester = settings.weightedSemesterFilter.first(),
                weightedExcluded = settings.weightedExcludedKcdm.first().toList(),
                hideWeekend = settings.hideWeekend.first(),
                hideInactiveCourses = settings.hideInactiveCourses.first(),
            ),
        )
    }

    /**
     * 整包覆盖恢复：一个 Room 事务写完五张表（中途失败不丢原数据），DataStore 各键随后写入，
     * 最后按恢复后的数据**重排全部本机提醒**。
     */
    suspend fun applyRestore(context: Context, snapshot: CloudSnapshot) {
        val repo = ScheduleRepository(context)
        val settings = repo.settings
        val db = AppDatabase.get(context)

        db.withTransaction {
            // course 无 clear()：三个 source 全删即整表清空；插入时 id 置 0 重新自增
            listOf(
                CourseEntity.SOURCE_IMPORT,
                CourseEntity.SOURCE_MANUAL,
                CourseEntity.SOURCE_SAMPLE,
            ).forEach { repo.clearCoursesBySource(it) }
            repo.insertCourses(snapshot.courses.map { it.toEntity() })

            repo.replaceSectionTimes(snapshot.sectionTimes.map {
                SectionTimeEntity(it.section, it.startTime, it.endTime)
            })
            repo.replaceGrades(snapshot.grades.map { it.toEntity() })
            repo.replaceExams(snapshot.exams.map { it.toEntity() })
            repo.replaceAllTodos(snapshot.todos)
        }

        val s = snapshot.settings
        settings.saveSemester(
            SettingsStore.SemesterConfig(
                xn = s.semester.xn, xq = s.semester.xq, name = s.semester.name,
                firstMonday = s.semester.firstMonday, totalWeeks = s.semester.totalWeeks,
                weekMondays = s.semester.weekMondays,
            ),
        )
        settings.setReminder(s.reminderEnabled, s.reminderMinutes)
        val theme = runCatching { SettingsStore.ThemeMode.valueOf(s.themeMode) }.getOrNull()
        if (theme != null) settings.setThemeMode(theme)
        settings.saveGradesMeta(s.gpaJson, s.gradesFetchedAt)
        settings.saveCreditMeta(s.xflbyqJson, s.bxkqkJson)
        settings.saveStudentProfile(
            SettingsStore.StudentProfile(
                xm = s.studentProfile.xm, xh = s.studentProfile.xh, yxmc = s.studentProfile.yxmc,
                zymc = s.studentProfile.zymc, bjmc = s.studentProfile.bjmc, njmc = s.studentProfile.njmc,
                xjsfzx = s.studentProfile.xjsfzx, xjsfzc = s.studentProfile.xjsfzc,
            ),
        )
        settings.saveWeightedFilter(s.weightedSemester, s.weightedExcluded.toSet())
        settings.setHideWeekend(s.hideWeekend)
        settings.setHideInactiveCourses(s.hideInactiveCourses)

        // 恢复完成后按新数据重排三类提醒（闹钟记录是设备本地的，必须在本机重建）
        runCatching { ClassReminderScheduler.reschedule(context) }
        runCatching { ExamReminderScheduler.reschedule(context) }
        runCatching { TodoReminderScheduler.reschedule(context) }
    }

    private fun CourseEntity.toDto() = CourseDto(
        taskId = taskId, name = name, teacher = teacher, location = location,
        dayOfWeek = dayOfWeek, startSection = startSection, endSection = endSection,
        weekBitmap = weekBitmap, colorIndex = colorIndex, source = source, hidden = hidden,
    )

    private fun CourseDto.toEntity() = CourseEntity(
        id = 0, taskId = taskId, name = name, teacher = teacher, location = location,
        dayOfWeek = dayOfWeek, startSection = startSection, endSection = endSection,
        weekBitmap = weekBitmap, colorIndex = colorIndex, source = source, hidden = hidden,
    )

    private fun GradeDto.toEntity() = GradeEntity(
        id = 0, kcdm = kcdm, kcmc = kcmc, xnxq = xnxq, xnxqmc = xnxqmc, kcxz = kcxz,
        kclb = kclb, xf = xf, zzcj = zzcj, bkcx = bkcx, yxmc = yxmc, sffx = sffx,
        pm = pm, zrs = zrs, khfs = khfs,
    )

    private fun ExamDto.toEntity() = ExamEntity(
        id = 0, kcdm = kcdm, kcmc = kcmc, kslx = kslx, kssjms = kssjms, ksrq = ksrq,
        kssj = kssj, jssj = jssj, cdmc = cdmc, zwh = zwh, jkjsbz = jkjsbz,
        kkyxmc = kkyxmc, xnxq = xnxq,
    )
}
