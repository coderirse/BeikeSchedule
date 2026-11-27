package com.caeamer.beikeschedule

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.caeamer.beikeschedule.data.backup.CloudSync
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.data.pref.dataStore
import com.caeamer.beikeschedule.ui.sync.UnifiedSyncUiState
import com.caeamer.beikeschedule.ui.sync.UnifiedSyncViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBackupInteractionTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private suspend fun prepareAccount(settings: SettingsStore) {
        settings.saveCloudAccount(SettingsStore.CloudAccount(xh = "backup-test-old", token = "test-only"))
        settings.setCloudSyncEnabled(true)
        settings.confirmCloudBackup("backup-test-old", null, revision = 10L, resumeAutomatic = true)
    }

    @Test fun completedBackupOnlyRebuildsWhenTheGenerationChanges() = runBlocking {
        CloudSync.withoutDirtyMarking {
            val original = app.dataStore.data.first()
            try {
                val settings = SettingsStore(app)
                prepareAccount(settings)
                settings.markCloudDirty()
                var requests = 0
                CloudSync.uploadIfChanged(app, force = false) { _, _, _ ->
                    requests++
                    11L
                }!!.getOrThrow()
                assertEquals(1, requests)
                assertFalse(settings.cloudBackupState.first().dirty)
                assertNull(CloudSync.uploadIfChanged(app, force = false) { _, _, _ -> error("Already confirmed") })

                settings.markCloudDirty()
                CloudSync.uploadIfChanged(app, force = false) { _, _, _ ->
                    requests++
                    if (requests == 2) settings.markCloudDirty()
                    10L + requests
                }!!.getOrThrow()
                assertEquals(3, requests)
                assertFalse(settings.cloudBackupState.first().dirty)
                assertEquals(13L, settings.cloudLastBackupAt.first())
            } finally {
                app.dataStore.updateData { original }
            }
        }
    }

    @Test fun queuedAutomaticBackupRespectsDisabledAndPausedStates() = runBlocking {
        CloudSync.withoutDirtyMarking {
            val original = app.dataStore.data.first()
            try {
                val settings = SettingsStore(app)
                prepareAccount(settings)
                settings.markCloudDirty()
                settings.setCloudSyncEnabled(false)
                val unexpectedUpload: suspend (String, String, Int) -> Long = { _, _, _ -> error("Unexpected upload") }
                assertNull(CloudSync.uploadIfChanged(app, force = false, putBackup = unexpectedUpload))

                settings.setCloudSyncEnabled(true)
                settings.pauseCloudAutoBackup()
                assertNull(CloudSync.uploadIfChanged(app, force = false, putBackup = unexpectedUpload))
                assertTrue(settings.cloudBackupState.first().dirty)
                CloudSync.uploadIfChanged(app, force = true) { _, _, _ -> 12L }!!.getOrThrow()
                assertTrue(settings.cloudBackupState.first().automaticAllowed)
                assertFalse(settings.cloudBackupState.first().dirty)
            } finally {
                app.dataStore.updateData { original }
            }
        }
    }

    @Test fun cancellingAccountSwitchNeverStartsTheImportScripts() = runBlocking {
        val original = app.dataStore.data.first()
        val vm = withContext(Dispatchers.Main) { UnifiedSyncViewModel(app) }
        val requests = Channel<String>(Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.jsRequests.collect { requests.send(it) }
        }
        try {
            CloudSync.withoutDirtyMarking { prepareAccount(SettingsStore(app)) }
            withContext(Dispatchers.Main) { vm.onPageReady() }
            assertEquals("import/jw_identity.js", withTimeout(5000) { requests.receive() })
            withContext(Dispatchers.Main) { vm.onIdentity("backup-test-new", "") }
            withTimeout(5000) { vm.state.first { it is UnifiedSyncUiState.NeedDecision } }
            withContext(Dispatchers.Main) { vm.answerAccountSwitch(false) }
            withTimeout(5000) { vm.state.first { it is UnifiedSyncUiState.Done } }

            assertTrue(requests.tryReceive().isFailure)
            val settings = SettingsStore(app)
            assertEquals("backup-test-old", settings.cloudAccount.first().xh)
            assertEquals(10L, settings.cloudLastBackupAt.first())
            assertTrue(settings.cloudBackupState.first().automaticAllowed)
        } finally {
            withContext(Dispatchers.Main) {
                vm.cancel()
                vm.viewModelScope.cancel()
            }
            collection.cancel()
            CloudSync.withoutDirtyMarking { app.dataStore.updateData { original } }
        }
    }
}
