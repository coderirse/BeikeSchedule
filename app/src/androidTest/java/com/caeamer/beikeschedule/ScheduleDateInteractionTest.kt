package com.caeamer.beikeschedule

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.backup.CloudSync
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.pref.dataStore
import com.caeamer.beikeschedule.ui.schedule.ScheduleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScheduleDateInteractionTest {
    @Test fun crossingIntoMondayRecomputesCurrentWeekWithoutResettingTheBrowsedWeek() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val original = app.dataStore.data.first()
        val vm = withContext(Dispatchers.Main) { ScheduleViewModel(app) }
        val firstMonday = LocalDate.of(2026, 11, 9)
        try {
            CloudSync.withoutDirtyMarking {
                SettingsStore(app).saveSemester(SettingsStore.SemesterConfig(firstMonday = firstMonday.toString()))
            }
            withTimeout(5000) { vm.uiState.first { it.loaded && it.semester.firstMonday == firstMonday.toString() } }
            withContext(Dispatchers.Main) {
                vm.selectWeek(5)
                vm.refreshToday(firstMonday.plusDays(6))
            }
            withTimeout(5000) { vm.uiState.first { it.currentWeek == 1 && it.selectedWeek == 5 } }
            withContext(Dispatchers.Main) { vm.refreshToday(firstMonday.plusWeeks(1)) }
            val monday = withTimeout(5000) { vm.uiState.first { it.currentWeek == 2 } }
            assertEquals(5, monday.selectedWeek)
        } finally {
            withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
            CloudSync.withoutDirtyMarking { app.dataStore.updateData { original } }
        }
    }
}
