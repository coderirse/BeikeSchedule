package com.example.beikeschedule.ui.settings

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.beikeschedule.R
import com.example.beikeschedule.data.pref.SettingsStore
import com.example.beikeschedule.ui.schedule.DropdownField

private const val REPO_URL = "https://github.com/coderirse/BeikeSchedule"

/** 设置 Tab：主题 / 检查更新 / GitHub 仓库 / 版权。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsState()
    val update by viewModel.update.collectAsState()
    val context = LocalContext.current
    var showUpdateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
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

            Spacer(Modifier.weight(1f))
            Text(
                "© 2026 caeamer. All rights reserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontWeight = FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    if (showUpdateDialog) {
        val u = update
        if (u is UpdateState.Available) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("发现新版本 v${u.latestVersion}") },
                text = {
                    Column {
                        if (u.notes.isNotBlank()) {
                            Text(u.notes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url)))
                        showUpdateDialog = false
                    }) { Text("前往下载") }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) { Text("关闭") }
                },
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
