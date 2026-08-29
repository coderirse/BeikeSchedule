package com.example.beikeschedule.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.data.repo.ScheduleRepository
import com.example.beikeschedule.import.parser.GradesParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** 更新检查状态。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val latestVersion: String, val notes: String, val url: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val themeMode: StateFlow<SettingsStore.ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.ThemeMode.SYSTEM)
    val update: StateFlow<UpdateState> = updateState
    val studentProfile: StateFlow<SettingsStore.StudentProfile> = settings.studentProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsStore.StudentProfile())
    val appVersion: StateFlow<String> = MutableStateFlow(
        runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: ""
        }.getOrDefault(""),
    )

    init {
        checkUpdate()
    }

    fun setThemeMode(mode: SettingsStore.ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** 清除成绩本地缓存（下次进教务 Tab 重新抓取）。 */
    fun clearGradesCache() {
        viewModelScope.launch {
            settings.saveGradesMeta("", 0L)
            ScheduleRepository(getApplication()).replaceGrades(emptyList())
        }
    }

    /** 检查 GitHub 最新 release 与已装版本比对（进入设置页自动触发，可手动重查）。 */
    fun checkUpdate() {
        if (updateState.value is UpdateState.Checking) return
        updateState.value = UpdateState.Checking
        viewModelScope.launch {
            updateState.value = fetchLatestRelease()
        }
    }

    private suspend fun fetchLatestRelease(): UpdateState = withContext(Dispatchers.IO) {
        val installed = runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0).versionName
        }.getOrNull()
            ?: return@withContext UpdateState.Failed("无法读取本机版本号")

        try {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "BeikeSchedule")
            if (conn.responseCode != 200) {
                return@withContext UpdateState.Failed("GitHub 请求失败（HTTP ${conn.responseCode}）")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val obj = Json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return@withContext UpdateState.Failed("响应缺少版本号")
            val notes = obj["body"]?.jsonPrimitive?.content.orEmpty().take(300)
            val url = obj["html_url"]?.jsonPrimitive?.content ?: REPO_URL
            when {
                GradesParser.compareVersions(tag, installed) > 0 ->
                    UpdateState.Available(tag.removePrefix("v"), notes, url)
                else -> UpdateState.UpToDate
            }
        } catch (e: Exception) {
            UpdateState.Failed("检查失败：${e.message}")
        }
    }

    private companion object {
        const val REPO_URL = "https://github.com/coderirse/BeikeSchedule"
        const val RELEASES_API = "https://api.github.com/repos/coderirse/BeikeSchedule/releases/latest"
    }
}
