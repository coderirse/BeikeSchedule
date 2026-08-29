package com.caeamer.beikeschedule.data.pref

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 学期与提醒等配置（DataStore）。 */
class SettingsStore(private val context: Context) {

    data class SemesterConfig(
        val xn: String = "",          // 学年，如 "2026-2027"
        val xq: String = "",          // 学期，如 "1"
        val name: String = "",        // 展示名，如 "2026-2027-1"
        val firstMonday: String = "", // 第 1 周周一，yyyy-MM-dd
        val totalWeeks: Int = 20,
        /**
         * 官方教学周日历：下标+1 = 教学周，值 = 该周周一（yyyy-MM-dd）。
         * 来自教务校历接口，长假周不占序号；为空时回退 firstMonday + totalWeeks 推算。
         */
        val weekMondays: List<String> = emptyList(),
    )

    /** 学籍快照（教务抓取时顺手存，"我的"页离线展示）。 */
    data class StudentProfile(
        val xm: String = "",       // 姓名
        val xh: String = "",       // 学号
        val yxmc: String = "",     // 学院
        val zymc: String = "",     // 专业
        val bjmc: String = "",     // 班级
        val njmc: String = "",     // 年级
        val xjsfzx: String = "",   // 是否在校（"1"=在校）
        val xjsfzc: String = "",   // 是否注册（"1"=已注册）
    ) {
        val isLoggedIn: Boolean get() = xh.isNotBlank()
    }

    /** 主题模式：跟随系统 / 浅色 / 深色。 */
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    private object Keys {
        val XN = stringPreferencesKey("semester_xn")
        val XQ = stringPreferencesKey("semester_xq")
        val NAME = stringPreferencesKey("semester_name")
        val FIRST_MONDAY = stringPreferencesKey("first_monday")
        val TOTAL_WEEKS = intPreferencesKey("total_weeks")
        val WEEK_MONDAYS = stringPreferencesKey("week_mondays")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
        val REMINDER_CODES = stringPreferencesKey("reminder_codes")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GPA_CACHE = stringPreferencesKey("gpa_cache")
        val GRADES_FETCHED_AT = longPreferencesKey("grades_fetched_at")
        val SP_XM = stringPreferencesKey("sp_xm")
        val SP_XH = stringPreferencesKey("sp_xh")
        val SP_YXMC = stringPreferencesKey("sp_yxmc")
        val SP_ZYMC = stringPreferencesKey("sp_zymc")
        val SP_BJMC = stringPreferencesKey("sp_bjmc")
        val SP_NJMC = stringPreferencesKey("sp_njmc")
        val SP_XJSFZX = stringPreferencesKey("sp_xjsfzx")
        val SP_XJSFZC = stringPreferencesKey("sp_xjsfzc")
        val WEIGHT_SEMESTER = stringPreferencesKey("weight_semester")
        val WEIGHT_EXCLUDED = stringPreferencesKey("weight_excluded")
        val HIDE_WEEKEND = booleanPreferencesKey("hide_weekend")
    }

    val semester: Flow<SemesterConfig> = context.dataStore.data.map { p ->
        SemesterConfig(
            xn = p[Keys.XN] ?: "",
            xq = p[Keys.XQ] ?: "",
            name = p[Keys.NAME] ?: "",
            firstMonday = p[Keys.FIRST_MONDAY] ?: "",
            totalWeeks = p[Keys.TOTAL_WEEKS] ?: 20,
            weekMondays = p[Keys.WEEK_MONDAYS].toWeekMondays(),
        )
    }

    suspend fun saveSemester(config: SemesterConfig) {
        context.dataStore.edit { p ->
            p[Keys.XN] = config.xn
            p[Keys.XQ] = config.xq
            p[Keys.NAME] = config.name
            p[Keys.FIRST_MONDAY] = config.firstMonday
            p[Keys.TOTAL_WEEKS] = config.totalWeeks
            p[Keys.WEEK_MONDAYS] = config.weekMondays.joinToString(",")
        }
    }

    val reminderEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.REMINDER_ENABLED] ?: false }

    val reminderMinutes: Flow<Int> =
        context.dataStore.data.map { it[Keys.REMINDER_MINUTES] ?: 15 }

    suspend fun setReminder(enabled: Boolean, minutesBefore: Int) {
        context.dataStore.edit { p ->
            p[Keys.REMINDER_ENABLED] = enabled
            p[Keys.REMINDER_MINUTES] = minutesBefore
        }
    }

    /** 已排课程提醒闹钟的 requestCode 集合（逗号分隔），用于精确取消。 */
    val reminderScheduledCodes: Flow<Set<Int>> = context.dataStore.data.map { p ->
        p[Keys.REMINDER_CODES]?.takeIf { it.isNotBlank() }
            ?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: emptySet()
    }

    suspend fun saveReminderScheduledCodes(codes: Set<Int>) {
        context.dataStore.edit { p ->
            p[Keys.REMINDER_CODES] = codes.joinToString(",")
        }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { p -> p[Keys.THEME_MODE] = mode.name }
    }

    /** GPA 概览缓存（getgpa 原始 JSON）与成绩刷新时间（epoch 毫秒，0=从未抓取）。 */
    val gpaCache: Flow<String> = context.dataStore.data.map { p -> p[Keys.GPA_CACHE] ?: "" }
    val gradesFetchedAt: Flow<Long> = context.dataStore.data.map { p -> p[Keys.GRADES_FETCHED_AT] ?: 0L }

    suspend fun saveGradesMeta(gpaJson: String, fetchedAt: Long) {
        context.dataStore.edit { p ->
            p[Keys.GPA_CACHE] = gpaJson
            p[Keys.GRADES_FETCHED_AT] = fetchedAt
        }
    }

    /** 学籍快照缓存（教务 Tab 抓成绩时顺手抓的 user/me 关键字段）。 */
    val studentProfile: Flow<StudentProfile> = context.dataStore.data.map { p ->
        StudentProfile(
            xm = p[Keys.SP_XM] ?: "",
            xh = p[Keys.SP_XH] ?: "",
            yxmc = p[Keys.SP_YXMC] ?: "",
            zymc = p[Keys.SP_ZYMC] ?: "",
            bjmc = p[Keys.SP_BJMC] ?: "",
            njmc = p[Keys.SP_NJMC] ?: "",
            xjsfzx = p[Keys.SP_XJSFZX] ?: "",
            xjsfzc = p[Keys.SP_XJSFZC] ?: "",
        )
    }

    suspend fun saveStudentProfile(profile: StudentProfile) {
        context.dataStore.edit { p ->
            p[Keys.SP_XM] = profile.xm
            p[Keys.SP_XH] = profile.xh
            p[Keys.SP_YXMC] = profile.yxmc
            p[Keys.SP_ZYMC] = profile.zymc
            p[Keys.SP_BJMC] = profile.bjmc
            p[Keys.SP_NJMC] = profile.njmc
            p[Keys.SP_XJSFZX] = profile.xjsfzx
            p[Keys.SP_XJSFZC] = profile.xjsfzc
        }
    }

    /** 加权成绩用户自定义：学期筛选（空=全部学期）与手动排除的课程 kcdm 集合。 */
    val weightedSemesterFilter: Flow<String> =
        context.dataStore.data.map { it[Keys.WEIGHT_SEMESTER] ?: "" }
    val weightedExcludedKcdm: Flow<Set<String>> =
        context.dataStore.data.map { p ->
            p[Keys.WEIGHT_EXCLUDED]?.takeIf { it.isNotBlank() }
                ?.split(",")?.toSet() ?: emptySet()
        }

    suspend fun saveWeightedFilter(semester: String, excluded: Set<String>) {
        context.dataStore.edit { p ->
            p[Keys.WEIGHT_SEMESTER] = semester
            p[Keys.WEIGHT_EXCLUDED] = excluded.joinToString(",")
        }
    }

    /** 课表是否隐藏周六周日列（周末无课时收窄网格）。 */
    val hideWeekend: Flow<Boolean> = context.dataStore.data.map { it[Keys.HIDE_WEEKEND] ?: false }

    suspend fun setHideWeekend(hidden: Boolean) {
        context.dataStore.edit { p -> p[Keys.HIDE_WEEKEND] = hidden }
    }

    private companion object {
        fun String?.toWeekMondays(): List<String> =
            this?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
    }
}
