package com.example.beikeschedule.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.beikeschedule.R
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.ui.settings.SettingsViewModel
import com.example.beikeschedule.ui.settings.UpdateState

private const val REPO_URL = "https://github.com/coderirse/BeikeSchedule"

/** 我的 Tab：学籍信息 + 主题 / 检查更新 / GitHub / 版本号 / 清缓存 + 版权。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: SettingsViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsState()
    val update by viewModel.update.collectAsState()
    val studentProfile by viewModel.studentProfile.collectAsState()
    val appVersion by viewModel.appVersion.collectAsState()
    val context = LocalContext.current
    var showUpdateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // 紧凑矮顶栏（外层 Scaffold 不消费状态栏 inset，这里自行处理）——透明透出整屏渐变
            Surface(color = Color.Transparent) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("我的", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // —— 学籍信息 ——（每行独立卡片：图标 + 标签 + 值）
            Text(
                "学籍信息",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (studentProfile.isLoggedIn) {
                if (studentProfile.xm.isNotBlank())
                    SettingsItemRow(icon = { Icon(Icons.Default.Person, null, Modifier.size(20.dp)) }, title = "姓名", value = studentProfile.xm)
                if (studentProfile.xh.isNotBlank())
                    SettingsItemRow(icon = { Icon(Icons.Default.Badge, null, Modifier.size(20.dp)) }, title = "学号", value = studentProfile.xh)
                if (studentProfile.yxmc.isNotBlank())
                    SettingsItemRow(icon = { Icon(Icons.Default.School, null, Modifier.size(20.dp)) }, title = "学院", value = studentProfile.yxmc)
                if (studentProfile.zymc.isNotBlank())
                    SettingsItemRow(icon = { Icon(Icons.Default.Book, null, Modifier.size(20.dp)) }, title = "专业", value = studentProfile.zymc)
                if (studentProfile.bjmc.isNotBlank())
                    SettingsItemRow(icon = { Icon(Icons.Default.Groups, null, Modifier.size(20.dp)) }, title = "班级", value = studentProfile.bjmc)
                if (studentProfile.njmc.isNotBlank())
                    SettingsItemRow(icon = { Icon(Icons.Default.Class, null, Modifier.size(20.dp)) }, title = "年级", value = studentProfile.njmc)
                if (studentProfile.xjsfzx.isNotBlank() || studentProfile.xjsfzc.isNotBlank()) {
                    SettingsItemRow(
                        icon = { Icon(Icons.Default.HowToReg, null, Modifier.size(20.dp)) },
                        title = "学籍状态",
                        value = (if (studentProfile.xjsfzx == "1") "在校" else "不在校") +
                            " · " + (if (studentProfile.xjsfzc == "1") "已注册" else "未注册"),
                    )
                }
            } else {
                SettingsItemRow(
                    icon = { Icon(Icons.Default.Info, null, Modifier.size(20.dp)) },
                    title = "未获取学籍信息",
                    value = "在教务 Tab 抓取一次成绩后自动显示",
                )
            }

            Spacer(Modifier.height(16.dp))

            // —— 通用功能 ——（每行独立卡片：图标 + 功能名 + 右侧按钮）
            Text(
                "通用",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val updateSubtitle = when (val u = update) {
                is UpdateState.Checking -> "正在检查…"
                is UpdateState.UpToDate -> "已是最新版本"
                is UpdateState.Available -> "发现新版本 v${u.latestVersion}"
                is UpdateState.Failed -> u.message
                UpdateState.Idle -> "检查 GitHub Releases"
            }
            SettingsItemRow(
                icon = { Icon(Icons.Default.SystemUpdate, null, Modifier.size(20.dp)) },
                title = "检查更新",
                value = updateSubtitle,
                trailing = if (update is UpdateState.Available) {
                    { TextButton(onClick = { showUpdateDialog = true }) { Text("查看") } }
                } else {
                    { TextButton(onClick = { viewModel.checkUpdate() }) { Text("检查") } }
                },
                onClick = {
                    val u = update
                    if (u is UpdateState.Available) showUpdateDialog = true else viewModel.checkUpdate()
                },
            )
            SettingsItemRow(
                icon = { Icon(painterResource(R.drawable.ic_github), "GitHub", Modifier.size(20.dp)) },
                title = "GitHub 仓库",
                value = "查看源码",
                trailing = { Text("›") },
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Badge, null, Modifier.size(20.dp)) },
                title = "版本",
                value = appVersion,
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.DeleteSweep, null, Modifier.size(20.dp)) },
                title = "清除成绩缓存",
                value = "删除本地成绩与 GPA",
                onClick = { viewModel.clearGradesCache() },
            )

            // —— 主题 ——
            Text(
                "外观",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Palette, null, Modifier.size(20.dp)) },
                title = "主题",
                value = when (themeMode) {
                    SettingsStore.ThemeMode.SYSTEM -> "跟随系统"
                    SettingsStore.ThemeMode.LIGHT -> "浅色"
                    SettingsStore.ThemeMode.DARK -> "深色"
                },
                trailing = { Text("›", style = MaterialTheme.typography.titleMedium) },
                onClick = {
                    val next = when (themeMode) {
                        SettingsStore.ThemeMode.SYSTEM -> SettingsStore.ThemeMode.LIGHT
                        SettingsStore.ThemeMode.LIGHT -> SettingsStore.ThemeMode.DARK
                        SettingsStore.ThemeMode.DARK -> SettingsStore.ThemeMode.SYSTEM
                    }
                    viewModel.setThemeMode(next)
                },
            )

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "© 2026 caeamer. All rights reserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showUpdateDialog) {
        val u = update
        if (u is UpdateState.Available) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("发现新版本 v${u.latestVersion}") },
                text = { if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)))
                        showUpdateDialog = false
                    }) { Text("前往下载") }
                },
                dismissButton = { TextButton(onClick = { showUpdateDialog = false }) { Text("关闭") } },
            )
        }
    }
}

/** 设置列表项行：左侧几何图标 + 中间标题/值 + 右侧可点按钮（仿 Net-USTB）。 */
@Composable
private fun SettingsItemRow(
    title: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标容器：圆角浅色底，里面放几何图标
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                icon?.invoke() ?: Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!value.isNullOrBlank()) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
