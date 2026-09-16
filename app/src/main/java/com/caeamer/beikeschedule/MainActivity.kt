package com.caeamer.beikeschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.caeamer.beikeschedule.data.pref.ScorePrivacy
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

    /**
     * 成绩隐私复位：App 退到后台（Home/切应用/锁屏/划掉后台）即把"显示成绩"复位为隐藏。
     * 前台内切换 Tab 不触发 onStop，因此显示状态在 App 内得以保持；
     * 旋转屏幕等配置变更会走 onStop 但不算退出，用 isChangingConfigurations 排除。
     *
     * 注意这只是**第二道防线**：最近任务（Recents）的缩略图取的是最后一帧已绘制画面，
     * 而 Compose 在 onStop 之后不保证再绘帧，所以"点小眼睛显示分数 → 立刻按 Home"
     * 的缩略图里分数仍然是可见的。第一道防线见 setContent 里的 setRecentsScreenshotEnabled。
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) ScorePrivacy.hide()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsStore(applicationContext)
        setContent {
            // 显示分数期间禁止把本 Activity 的画面放进最近任务缩略图。
            // 用 LaunchedEffect 而非 collectAsState：只有这一个效果关心这个状态，
            // 用 collectAsState 会让每次点小眼睛都重组整棵主题树。
            LaunchedEffect(Unit) {
                ScorePrivacy.hidden.collect { hidden ->
                    setRecentsScreenshotEnabled(hidden)
                }
            }
            val themeMode by settings.themeMode.collectAsState(initial = SettingsStore.ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                SettingsStore.ThemeMode.LIGHT -> false
                SettingsStore.ThemeMode.DARK -> true
                SettingsStore.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            BeikeScheduleTheme(darkTheme = darkTheme) {
                var tab by rememberSaveable { mutableStateOf("schedule") }
                var showImport by rememberSaveable { mutableStateOf(false) }
                // 导入页的 WebView 展示的是浅底教务页面，需要临时切成深色状态栏图标
                var importLightPage by remember { mutableStateOf(false) }

                // 状态栏/导航栏图标明暗：themeMode 只是 Compose 内部的主题选择，
                // 不会改资源 uiMode，而 enableEdgeToEdge() 的 SystemBarStyle.auto 只看 uiMode
                // （本应用主题是 android:Theme.Material.Light.NoActionBar，恒为 notnight），
                // 所以必须自己按 darkTheme 设置，否则"系统浅色 + 应用深色"时是深图标压在近黑渐变上。
                val darkIcons = if (showImport && importLightPage) true else !darkTheme
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = darkIcons
                        isAppearanceLightNavigationBars = darkIcons
                    }
                }

                if (showImport) {
                    // 导入为全屏流程，不显示底部 Tab；返回键由 ImportScreen 内的 BackHandler 接管
                    ImportScreen(
                        onDone = { showImport = false },
                        onLightBackgroundVisible = { importLightPage = it },
                    )
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
