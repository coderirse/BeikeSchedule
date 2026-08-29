package com.caeamer.beikeschedule

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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.import.ImportScreen
import com.caeamer.beikeschedule.ui.grades.GradesScreen
import com.caeamer.beikeschedule.ui.profile.ProfileScreen
import com.caeamer.beikeschedule.ui.schedule.ScheduleScreen
import com.caeamer.beikeschedule.ui.theme.BeikeScheduleTheme
import com.caeamer.beikeschedule.ui.theme.CourseColors

/** 底部三个 Tab 的横向内容（课表/教务/我的）。需在 RowScope 内调用（用 weight 均分）。 */
@Composable
private fun RowScope.BottomTabContent(tab: String, onTab: (String) -> Unit) {
    TabItem(
        selected = tab == "schedule",
        onClick = { onTab("schedule") },
        icon = Icons.Default.CalendarMonth,
        label = "课表",
        modifier = Modifier.weight(1f),
    )
    TabItem(
        selected = tab == "jw",
        onClick = { onTab("jw") },
        icon = Icons.Default.School,
        label = "教务",
        modifier = Modifier.weight(1f),
    )
    TabItem(
        selected = tab == "mine",
        onClick = { onTab("mine") },
        icon = Icons.Default.Person,
        label = "我的",
        modifier = Modifier.weight(1f),
    )
}

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
                    // 整屏渐变仅在「课表页」开启：浅色暖渐变/暗色暗渐变，其他页用主题默认背景
                    val useGradient = tab == "schedule"
                    Box(
                        Modifier.fillMaxSize().background(
                            if (useGradient) {
                                if (darkTheme) CourseColors.scheduleGradientDark else CourseColors.scheduleGradient
                            } else {
                                SolidColor(Color.Transparent)
                            },
                        ),
                    ) {
                        Scaffold(
                            // 内容区不消费系统栏 insets：各页顶栏自行处理状态栏
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            // 开启渐变色透明，否则用主题默认背景
                            containerColor = if (useGradient) Color.Transparent else MaterialTheme.colorScheme.background,
                            bottomBar = {
                                // 底部栏：课表页透出渐变，其他页用默认 surface 色调
                                if (useGradient) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                            .height(56.dp)
                                            .padding(horizontal = 8.dp),
                                    ) {
                                        BottomTabContent(tab, onTab = { tab = it })
                                    }
                                } else {
                                    // 非课表页：默认 surface 底色；去掉 tonalElevation 避免顶部阴影色块
                                    Surface(color = MaterialTheme.colorScheme.surface) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .navigationBarsPadding()
                                                .height(56.dp)
                                                .padding(horizontal = 8.dp),
                                        ) {
                                            BottomTabContent(tab, onTab = { tab = it })
                                        }
                                    }
                                }
                            },
                        ) { padding ->
                            Box(Modifier.padding(padding)) {
                                when (tab) {
                                    "jw" -> GradesScreen()
                                    "mine" -> ProfileScreen()
                                    else -> ScheduleScreen(onImportClick = { showImport = true }, darkTheme = darkTheme)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
