package com.caeamer.beikeschedule.ui.profile

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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
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
import com.caeamer.beikeschedule.R
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.ui.settings.SettingsViewModel
import com.caeamer.beikeschedule.ui.settings.UpdateState

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
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

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
            // —— 学籍信息 ——（一张大卡片，内部 label:value 多行）
            Text(
                "学籍信息",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (studentProfile.isLoggedIn) {
                        // 账户头：姓名大字 + 学号小字，其余字段降为普通行
                        if (studentProfile.xm.isNotBlank()) {
                            Text(
                                studentProfile.xm,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (studentProfile.xh.isNotBlank()) {
                            Text(
                                studentProfile.xh,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (studentProfile.xm.isNotBlank() || studentProfile.xh.isNotBlank()) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        }
                        if (studentProfile.yxmc.isNotBlank()) ProfileRow("学院", studentProfile.yxmc)
                        if (studentProfile.zymc.isNotBlank()) ProfileRow("专业", studentProfile.zymc)
                        if (studentProfile.bjmc.isNotBlank()) ProfileRow("班级", studentProfile.bjmc)
                        if (studentProfile.njmc.isNotBlank()) ProfileRow("年级", studentProfile.njmc)
                        if (studentProfile.xjsfzx.isNotBlank() || studentProfile.xjsfzc.isNotBlank()) {
                            ProfileRow(
                                "学籍状态",
                                (if (studentProfile.xjsfzx == "1") "在校" else "不在校") +
                                    " · " + (if (studentProfile.xjsfzc == "1") "已注册" else "未注册"),
                            )
                        }
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
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SettingsViewModel.REPO_URL)),
                    )
                },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Badge, null, Modifier.size(20.dp)) },
                title = "版本",
                value = appVersion,
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.MailOutline, null, Modifier.size(20.dp)) },
                title = "联系开发者",
                value = "caeamer@163.com · 问题反馈与建议",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:caeamer@163.com")).apply {
                            putExtra(Intent.EXTRA_SUBJECT, "贝壳课表 反馈")
                            putExtra(Intent.EXTRA_TEXT, "（请描述你遇到的问题或建议；版本 $appVersion）")
                        },
                    )
                },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.DeleteSweep, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                title = "清除成绩缓存",
                value = "删除本地成绩与 GPA",
                destructive = true,
                onClick = { showClearCacheConfirm = true },
            )

            // —— 外部系统 ——（浏览器跳转；课程平台/实践平台地址待补后追加）
            Text(
                "外部系统",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Grade, null, Modifier.size(20.dp)) },
                title = "评教系统",
                value = "教学评价",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SettingsViewModel.PINGJIAO_URL)),
                    )
                },
            )
            SettingsItemRow(
                icon = { Icon(Icons.Default.Science, null, Modifier.size(20.dp)) },
                title = "大创 / SRTP",
                value = "大学生创新创业训练计划",
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SettingsViewModel.SRTP_URL)),
                    )
                },
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
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                // 循环切换不可发现（用户不知道点一下会变成什么），改为弹窗三选一
                onClick = { showThemeDialog = true },
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

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("主题") },
            text = {
                Column {
                    listOf(
                        SettingsStore.ThemeMode.SYSTEM to "跟随系统",
                        SettingsStore.ThemeMode.LIGHT to "浅色",
                        SettingsStore.ThemeMode.DARK to "深色",
                    ).forEach { (mode, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                viewModel.setThemeMode(mode)
                                showThemeDialog = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    viewModel.setThemeMode(mode)
                                    showThemeDialog = false
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("取消") }
            },
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("清除成绩缓存") },
            text = { Text("将删除本地的成绩与 GPA 数据，下次进入教务 Tab 需重新抓取。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearGradesCache()
                    showClearCacheConfirm = false
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") } },
        )
    }
}

/** 设置列表项行：左侧几何图标 + 中间标题/值 + 右侧可点按钮（仿 Net-USTB）。 */
@Composable
private fun SettingsItemRow(
    title: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
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
                    .background(
                        if (destructive) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                icon?.invoke() ?: Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
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

/** 学籍信息大卡片内的 label:value 行。 */
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
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
