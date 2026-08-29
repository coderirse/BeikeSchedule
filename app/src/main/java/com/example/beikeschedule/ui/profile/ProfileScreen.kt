package com.example.beikeschedule.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.beikeschedule.R
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.ui.schedule.DropdownField
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
        topBar = { TopAppBar(title = { Text("我的") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // —— 学籍信息 ——
            SectionTitle("学籍信息")
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (studentProfile.isLoggedIn) {
                        ProfileRow("姓名", studentProfile.xm)
                        ProfileRow("学号", studentProfile.xh)
                        ProfileRow("学院", studentProfile.yxmc)
                        ProfileRow("专业", studentProfile.zymc)
                        ProfileRow("班级", studentProfile.bjmc)
                        ProfileRow("年级", studentProfile.njmc)
                        ProfileRow(
                            "学籍状态",
                            (if (studentProfile.xjsfzx == "1") "在校" else "不在校") +
                                " · " + (if (studentProfile.xjsfzc == "1") "已注册" else "未注册"),
                        )
                    } else {
                        Text(
                            "未获取学籍信息",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "在教务 Tab 抓取一次成绩后自动显示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // —— 外观 ——
            SectionTitle("外观")
            DropdownField(
                label = "主题",
                options = listOf(
                    SettingsStore.ThemeMode.SYSTEM to "跟随系统",
                    SettingsStore.ThemeMode.LIGHT to "浅色",
                    SettingsStore.ThemeMode.DARK to "深色",
                ),
                selected = themeMode,
                onSelect = { viewModel.setThemeMode(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // —— 通用 ——
            SectionTitle("通用")
            val subtitle = when (val u = update) {
                is UpdateState.Checking -> "正在检查…"
                is UpdateState.UpToDate -> "已是最新版本"
                is UpdateState.Available -> "发现新版本 v${u.latestVersion}，点击查看"
                is UpdateState.Failed -> u.message
                UpdateState.Idle -> "检查 GitHub Releases 是否有新版本"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val u = update
                        if (u is UpdateState.Available) showUpdateDialog = true
                        else viewModel.checkUpdate()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("检查更新", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (update is UpdateState.Failed) MaterialTheme.colorScheme.error
                        else if (update is UpdateState.Available) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (update is UpdateState.Available) {
                    TextButton(onClick = { showUpdateDialog = true }) { Text("查看") }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_github),
                    contentDescription = "GitHub",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("GitHub 仓库", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        REPO_URL.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("版本", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    appVersion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.clearGradesCache() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("清除成绩缓存", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "删除本地成绩与 GPA 数据，下次进入教务 Tab 重新抓取",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
