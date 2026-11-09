package com.caeamer.beikeschedule.ui.pet

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlin.random.Random

// 独立于核心 settings：宠物设置不会引起课表、云同步或提醒排期的写入。
private val Context.petDataStore by preferencesDataStore("schedule_pet")

enum class PetFrequency(val label: String, val minimumSeconds: Int, val maximumSeconds: Int) {
    QUIET("安静", 180, 300), MEDIUM("适中", 60, 120), LIVELY("活泼", 30, 60);
    fun delayMillis(random: Random = Random.Default): Long =
        random.nextInt(minimumSeconds, maximumSeconds + 1) * 1000L
}

data class PetPreferences(
    val enabled: Boolean = true,
    val frequency: PetFrequency = PetFrequency.MEDIUM,
    val address: String = "同学",
    val courseHints: Boolean = true,
    val examHints: Boolean = true,
    val chatter: Boolean = true,
)

class PetPreferencesStore(context: Context) {
    private val store = context.applicationContext.petDataStore
    private val enabled = booleanPreferencesKey("enabled")
    private val frequency = stringPreferencesKey("frequency")
    private val address = stringPreferencesKey("address")
    private val courses = booleanPreferencesKey("courses")
    private val exams = booleanPreferencesKey("exams")
    private val chatter = booleanPreferencesKey("chatter")
    val preferences = store.data.map { p -> PetPreferences(
        enabled = p[enabled] ?: true,
        frequency = PetFrequency.entries.firstOrNull { it.name == p[frequency] } ?: PetFrequency.MEDIUM,
        address = p[address] ?: "同学",
        courseHints = p[courses] ?: true, examHints = p[exams] ?: true, chatter = p[chatter] ?: true,
    ) }
    suspend fun setEnabled(value: Boolean) { store.edit { it[enabled] = value } }
    suspend fun setFrequency(value: PetFrequency) { store.edit { it[frequency] = value.name } }
    suspend fun setAddress(value: String) { store.edit { it[address] = value.trim().filterNot(Char::isISOControl).take(20) } }
    suspend fun setCourseHints(value: Boolean) { store.edit { it[courses] = value } }
    suspend fun setExamHints(value: Boolean) { store.edit { it[exams] = value } }
    suspend fun setChatter(value: Boolean) { store.edit { it[chatter] = value } }
}
