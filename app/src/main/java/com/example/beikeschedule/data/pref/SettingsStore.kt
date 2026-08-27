package com.example.beikeschedule.data.pref

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    )

    private object Keys {
        val XN = stringPreferencesKey("semester_xn")
        val XQ = stringPreferencesKey("semester_xq")
        val NAME = stringPreferencesKey("semester_name")
        val FIRST_MONDAY = stringPreferencesKey("first_monday")
        val TOTAL_WEEKS = intPreferencesKey("total_weeks")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
    }

    val semester: Flow<SemesterConfig> = context.dataStore.data.map { p ->
        SemesterConfig(
            xn = p[Keys.XN] ?: "",
            xq = p[Keys.XQ] ?: "",
            name = p[Keys.NAME] ?: "",
            firstMonday = p[Keys.FIRST_MONDAY] ?: "",
            totalWeeks = p[Keys.TOTAL_WEEKS] ?: 20,
        )
    }

    suspend fun saveSemester(config: SemesterConfig) {
        context.dataStore.edit { p ->
            p[Keys.XN] = config.xn
            p[Keys.XQ] = config.xq
            p[Keys.NAME] = config.name
            p[Keys.FIRST_MONDAY] = config.firstMonday
            p[Keys.TOTAL_WEEKS] = config.totalWeeks
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
}
