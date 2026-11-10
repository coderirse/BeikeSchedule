package com.caeamer.beikeschedule.ui.pet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caeamer.beikeschedule.data.repo.ScheduleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PetViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PetPreferencesStore(app)
    private val repo = ScheduleRepository(app)
    val preferences = store.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val facts = combine(repo.courses, repo.sectionTimes, repo.settings.semester, repo.exams, repo.settings.reminderMinutes) {
        courses, sections, semester, exams, minutes -> PetFacts(courses, sections, semester, exams, minutes)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    // Activity 内去重，避免切 Tab 重复说同一场提醒；冷启动重新提示是一次新的前台会话。
    val seenHints = linkedSetOf<String>()
    val recentLines = ArrayDeque<String>()
    data class Welcome(val id: Long, val absenceMillis: Long, val firstVisit: Boolean)
    val welcome = MutableStateFlow<Welcome?>(null)
    private var visible = false
    private var visitSerial = 0L
    private val visitMutex = Mutex()
    var interactionPaused = false
    val dialogue: PetDialogueProvider = LocalPetDialogueProvider()
    fun enabled(value: Boolean) { viewModelScope.launch { store.setEnabled(value) } }
    fun frequency(value: PetFrequency) { viewModelScope.launch { store.setFrequency(value) } }
    fun address(value: String) { viewModelScope.launch { store.setAddress(value) } }
    fun courses(value: Boolean) { viewModelScope.launch { store.setCourseHints(value) } }
    fun exams(value: Boolean) { viewModelScope.launch { store.setExamHints(value) } }
    fun chatter(value: Boolean) { viewModelScope.launch { store.setChatter(value) } }
    fun welcomePolicy(value: PetWelcomePolicy) { viewModelScope.launch { store.setWelcome(value) } }
    fun visibility(value: Boolean) {
        if (visible == value) return
        visible = value
        val serial = ++visitSerial
        viewModelScope.launch {
            visitMutex.withLock {
                val now = System.currentTimeMillis()
                val previous = store.visit()
                val policy = preferences.value?.welcomePolicy ?: PetWelcomePolicy.EVERY_ENTRY
                val should = value && !interactionPaused && policy.shouldWelcome(now, previous.lastWelcome)
                welcome.value = if (should) Welcome(serial, (now - previous.lastSeen).coerceAtLeast(0), previous.lastSeen == 0L) else null
                store.recordVisit(now, should)
            }
        }
    }
    fun consumeWelcome(id: Long) { if (welcome.value?.id == id) welcome.value = null }
    fun rememberHint(id: String) { seenHints += id; while (seenHints.size > 64) seenHints.remove(seenHints.first()) }
    fun rememberLine(id: String) { recentLines.addLast(id); while (recentLines.size > 12) recentLines.removeFirst() }
}
