package com.example.beikeschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.import.ImportScreen
import com.example.beikeschedule.ui.grades.GradesScreen
import com.example.beikeschedule.ui.schedule.ScheduleScreen
import com.example.beikeschedule.ui.settings.SettingsScreen
import com.example.beikeschedule.ui.theme.BeikeScheduleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsStore(applicationContext)
        setContent {
            val themeMode by settings.themeMode.collectAsState(initial = SettingsStore.ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                SettingsStore.ThemeMode.LIGHT -> false
                SettingsStore.ThemeMode.DARK -> true
                SettingsStore.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            BeikeScheduleTheme(darkTheme = darkTheme) {
                var tab by rememberSaveable { mutableStateOf("schedule") }
                var showImport by rememberSaveable { mutableStateOf(false) }

                if (showImport) {
                    // 导入为全屏流程（含返回），不显示底部 Tab
                    ImportScreen(onDone = { showImport = false })
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == "schedule",
                                    onClick = { tab = "schedule" },
                                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                    label = { Text("课表") },
                                )
                                NavigationBarItem(
                                    selected = tab == "jw",
                                    onClick = { tab = "jw" },
                                    icon = { Icon(Icons.Default.School, contentDescription = null) },
                                    label = { Text("教务") },
                                )
                                NavigationBarItem(
                                    selected = tab == "settings",
                                    onClick = { tab = "settings" },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("设置") },
                                )
                            }
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            when (tab) {
                                "jw" -> GradesScreen()
                                "settings" -> SettingsScreen()
                                else -> ScheduleScreen(onImportClick = { showImport = true })
                            }
                        }
                    }
                }
            }
        }
    }
}
