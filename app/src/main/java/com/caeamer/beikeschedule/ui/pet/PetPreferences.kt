package com.caeamer.beikeschedule.ui.pet

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlin.random.Random

// 独立于核心 settings：宠物设置不会引起课表、云同步或提醒排期的写入。
private val Context.petDataStore by preferencesDataStore("schedule_pet")

enum class PetFrequency(val label: String, val minimumSeconds: Int, val maximumSeconds: Int) {
    QUIET("安静", 180, 300), MEDIUM("适中", 60, 120), LIVELY("活泼", 30, 60);
    fun delayMillis(random: Random = Random.Default): Long =
        random.nextInt(minimumSeconds, maximumSeconds + 1) * 1000L
}

enum class PetWelcomePolicy(val label: String, val intervalMillis: Long) {
    EVERY_ENTRY("每次进入", 0), ONE_MINUTE("间隔 1 分钟", 60_000),
    FIVE_MINUTES("间隔 5 分钟", 300_000), THIRTY_MINUTES("间隔 30 分钟", 1_800_000), OFF("关闭欢迎", Long.MAX_VALUE);
    fun shouldWelcome(now: Long, lastWelcome: Long): Boolean = this != OFF &&
        (lastWelcome <= 0 || now < lastWelcome || now - lastWelcome >= intervalMillis)
}
data class PetVisit(val lastSeen: Long = 0, val lastWelcome: Long = 0)

data class PetPreferences(
    val enabled: Boolean = true,
    val frequency: PetFrequency = PetFrequency.MEDIUM,
    val address: String = "同学",
    val courseHints: Boolean = true,
    val examHints: Boolean = true,
    val chatter: Boolean = true,
    val welcomePolicy: PetWelcomePolicy = PetWelcomePolicy.EVERY_ENTRY,
)

class PetPreferencesStore(context: Context) {
    private val store = context.applicationContext.petDataStore
    private val enabled = booleanPreferencesKey("enabled")
    private val frequency = stringPreferencesKey("frequency")
    private val address = stringPreferencesKey("address")
    private val courses = booleanPreferencesKey("courses")
    private val exams = booleanPreferencesKey("exams")
    private val chatter = booleanPreferencesKey("chatter")
    private val welcome = stringPreferencesKey("welcome_policy")
    private val lastSeen = longPreferencesKey("last_seen")
    private val lastWelcome = longPreferencesKey("last_welcome")
    val preferences = store.data.map { p -> PetPreferences(
        enabled = p[enabled] ?: true,
        frequency = PetFrequency.entries.firstOrNull { it.name == p[frequency] } ?: PetFrequency.MEDIUM,
        address = p[address] ?: "同学",
        courseHints = p[courses] ?: true, examHints = p[exams] ?: true, chatter = p[chatter] ?: true,
        welcomePolicy = PetWelcomePolicy.entries.firstOrNull { it.name == p[welcome] } ?: PetWelcomePolicy.EVERY_ENTRY,
    ) }.distinctUntilChanged()
    suspend fun setEnabled(value: Boolean) { store.edit { it[enabled] = value } }
    suspend fun setFrequency(value: PetFrequency) { store.edit { it[frequency] = value.name } }
    suspend fun setAddress(value: String) { store.edit { it[address] = value.trim().filterNot(Char::isISOControl).take(20) } }
    suspend fun setCourseHints(value: Boolean) { store.edit { it[courses] = value } }
    suspend fun setExamHints(value: Boolean) { store.edit { it[exams] = value } }
    suspend fun setChatter(value: Boolean) { store.edit { it[chatter] = value } }
    suspend fun setWelcome(value: PetWelcomePolicy) { store.edit { it[welcome] = value.name } }
    suspend fun visit(): PetVisit = store.data.first().let { PetVisit(it[lastSeen] ?: 0L, it[lastWelcome] ?: 0L) }
    suspend fun recordVisit(now: Long, welcomed: Boolean = false) {
        store.edit { it[lastSeen] = now; if (welcomed) it[lastWelcome] = now }
    }
}
