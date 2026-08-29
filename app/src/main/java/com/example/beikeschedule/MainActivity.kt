package com.example.beikeschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.import.ImportScreen
import com.example.beikeschedule.ui.grades.GradesScreen
import com.example.beikeschedule.ui.profile.ProfileScreen
import com.example.beikeschedule.ui.schedule.ScheduleScreen
import com.example.beikeschedule.ui.theme.BeikeScheduleTheme
import com.example.beikeschedule.ui.theme.CourseColors

/** 底部 Tab 项（紧凑单列：图标在上文字在下，无默认 padding）。 */
@Composable
private fun TabItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.height(22.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
                    // 整屏一张连通的暖色渐变（含顶栏/底部 Tab/内容区），所有子层透明透出
                    Box(Modifier.fillMaxSize().background(CourseColors.scheduleGradient)) {
                        Scaffold(
                            // 内容区不消费系统栏 insets：各页顶栏自行处理状态栏，
                            // 否则教务/我的页的 TopAppBar 会与这里双重叠加状态栏高度 → 顶部大片空白
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            containerColor = Color.Transparent,
                            bottomBar = {
                                // 底部栏自己处理手势条高度，背景透明透出渐变
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .height(56.dp)
                                        .padding(horizontal = 8.dp),
                                ) {
                                    TabItem(
                                        selected = tab == "schedule",
                                        onClick = { tab = "schedule" },
                                        icon = Icons.Default.CalendarMonth,
                                        label = "课表",
                                        modifier = Modifier.weight(1f),
                                    )
                                    TabItem(
                                        selected = tab == "jw",
                                        onClick = { tab = "jw" },
                                        icon = Icons.Default.School,
                                        label = "教务",
                                        modifier = Modifier.weight(1f),
                                    )
                                    TabItem(
                                        selected = tab == "mine",
                                        onClick = { tab = "mine" },
                                        icon = Icons.Default.Person,
                                        label = "我的",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            },
                        ) { padding ->
                            Box(Modifier.padding(padding)) {
                                when (tab) {
                                    "jw" -> GradesScreen()
                                    "mine" -> ProfileScreen()
                                    else -> ScheduleScreen(onImportClick = { showImport = true })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
