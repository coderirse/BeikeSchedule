package com.caeamer.beikeschedule.ui.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.caeamer.beikeschedule.data.local.CourseEntity
import com.caeamer.beikeschedule.data.pref.SettingsStore
import com.caeamer.beikeschedule.reminder.ClassReminderScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 学期设置全屏页（替代旧 SemesterSettingsDialog）：
 * - 分组卡片风格与「我的」页一致；旧弹窗"学期信息要点保存、开关类却即时生效"的混杂语义
 *   统一为**全部即时生效**，没有保存/取消按钮，返回即完成；
 * - 承载方式与 UnifiedSyncScreen 相同：由 MainActivity 在显示本页时替换掉底部 Tab 框架；
 * - 通知权限申请内聚到本页（旧实现在 ScheduleScreen，为对话框服务）。
 *
 * 学期名是唯一"延迟落盘"的字段：每敲一键都写 DataStore 会打断输入法组合，
 * 失焦 / 返回时一次性写入；开学日期与总周数仍即点即存。保存一律从本地草稿
 * 构造配置（[saveSemesterInfo]），异步链路上不会出现"旧 config 覆盖新字段"的回退。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterSettingsPage(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reminderEnabled by viewModel.reminderEnabled.collectAsStateWithLifecycle()
    val reminderMinutes by viewModel.reminderMinutes.collectAsStateWithLifecycle()
    val hideWeekend by viewModel.hideWeekend.collectAsStateWithLifecycle()
    val reminderSchedule by viewModel.reminderSchedule.collectAsStateWithLifecycle()
    val current = state.semester
    val context = LocalContext.current

    var nameDraft by rememberSaveable { mutableStateOf(current.name) }
    var firstMonday by rememberSaveable { mutableStateOf(current.firstMonday) }
    var totalWeeks by rememberSaveable { mutableIntStateOf(current.totalWeeks) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    // 总周数不得低于官方校历周数：否则"（本周）"永远不出现、超出部分的课会被静默截断
    val minWeeks = current.weekMondays.size

    fun saveSemesterInfo(
        name: String = nameDraft,
        firstMondayOverride: String? = null,
        totalWeeksOverride: Int? = null,
    ) {
        // 参数名不能与本地状态同名（默认值会引用参数自身导致未初始化），
        // 用 override 形式表达"本次只改某一个字段"
        val fm = firstMondayOverride ?: firstMonday
        val tw = totalWeeksOverride ?: totalWeeks
        viewModel.saveSemester(
            current.copy(
                name = name.trim(),
                firstMonday = fm,
                totalWeeks = if (minWeeks > 0) tw.coerceAtLeast(minWeeks) else tw,
            ),
        )
    }

    /** 学期名草稿有变化才落盘（失焦 / 返回都会调用，重复调用无副作用）。 */
    fun flushNameDraft() {
        if (nameDraft.trim() != current.name) saveSemesterInfo()
    }

    fun close() {
        flushNameDraft()
        onBack()
    }
    BackHandler { close() }

    // 权限诊断必须在**每次回到前台**时重算：点「去开启通知 / 去开启精确闹钟」跳系统设置
    // 授权后返回，Activity 通常不重建、组合原样保留，裸 remember 只在进入组合时算一次，
    // 页面会一直挂着"已被系统关闭"的过期结论，用户得退出重进才看到真相。
    var notificationsBlocked by remember { mutableStateOf(areNotificationsBlocked(context)) }
    var exactAlarmBlocked by remember { mutableStateOf(isExactAlarmBlocked(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsBlocked = areNotificationsBlocked(context)
        exactAlarmBlocked = isExactAlarmBlocked(context)
    }

    // 开启上课提醒前需要先拿到通知权限（Android 13+）；saveable：权限系统弹窗由独立
    // Activity 承载，期间旋转会重建本组合，裸 remember 会丢掉"用户要开提醒"的意图
    var pendingEnableReminder by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingEnableReminder) viewModel.setReminder(true, reminderMinutes)
        pendingEnableReminder = false
    }

    // 恢复必须按整组：隐藏是按合并组做的（一张卡 N 行），只恢复一行会留下
    // 一张"残废"卡片（例如只剩第 7 周有课），且隐藏列表里还有同名项要反复点。
    fun restoreCourse(course: CourseEntity) {
        val group = state.courses.filter { it.name == course.name && it.source == course.source }
        if (group.isEmpty()) viewModel.setCourseHidden(course.id, false)
        else viewModel.setCoursesHidden(group.map { it.id }, false)
    }

    Scaffold(
        // 外层（MainActivity）的 Scaffold 不消费系统栏 insets，这里自行处理
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(color = Color.Transparent) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { close() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("学期设置", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SectionLabel("学期信息")
            SectionCard {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("学期名（如 2026-2027-1）") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) flushNameDraft() },
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RowShape)
                            .clickable { showDatePicker = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("开学日期", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            firstMonday.ifBlank { "未设置" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 下拉必须包含当前值：导入会写入校历自己的总周数，若不在枚举里，
                    // ExposedDropdownMenuBox 会渲染成空白（看起来像"没选"）；
                    // 小于官方校历长度的选项直接不出现（旧的"选了再静默校正"提示就此取消）
                    DropdownField(
                        label = "总周数",
                        options = (listOf(16, 18, 20, 22, 25) + totalWeeks)
                            .distinct().sorted()
                            .filter { minWeeks <= 0 || it >= minWeeks || it == totalWeeks }
                            .map { it to "${it}周" },
                        selected = totalWeeks,
                        onSelect = {
                            totalWeeks = it
                            saveSemesterInfo(totalWeeksOverride = it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (current.weekMondays.isNotEmpty()) {
                            "教学周日历：来自教务系统（${current.weekMondays.size} 周，含放假跳周），" +
                                "日期与当前周以官方日历为准，上方开学日期仅作备用"
                        } else {
                            "教学周日历：未导入，按开学日期逐周推算；从教务导入课表后自动获取官方日历"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("课表显示")
            SectionCard {
                SwitchRow(
                    title = "隐藏周末",
                    subtitle = "周末无课时收窄网格，工作日列更宽",
                    checked = hideWeekend,
                    onChecked = { viewModel.setHideWeekend(it) },
                )
            }

            SectionLabel("上课提醒")
            SectionCard {
                Column {
                    SwitchRow(
                        title = "上课提醒",
                        subtitle = null,
                        checked = reminderEnabled,
                        onChecked = { want ->
                            if (want) {
                                if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    viewModel.setReminder(true, reminderMinutes)
                                } else {
                                    pendingEnableReminder = true
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                viewModel.setReminder(false, reminderMinutes)
                            }
                        },
                    )
                    if (reminderEnabled) {
                        Column(
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DropdownField(
                                label = "提前提醒",
                                options = listOf(5, 10, 15, 20, 30, 45).map { it to "$it 分钟" },
                                selected = reminderMinutes,
                                onSelect = { viewModel.setReminder(true, it) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // —— 提醒状态诊断 ——
                            // 这个功能出过两次"偶发不提醒"。把"排上了没有 / 下次什么时候响 /
                            // 通知是不是被系统关了"直接摆到界面上，出问题不用再抓 logcat。
                            if (notificationsBlocked) {
                                Text(
                                    "通知已被系统关闭，上课提醒不会弹出。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = { openNotificationSettings(context) }) { Text("去开启通知") }
                            } else {
                                Text(
                                    reminderStatusText(reminderSchedule),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (exactAlarmBlocked) {
                                Text(
                                    "系统未授予精确闹钟权限，提醒可能延迟几分钟",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = {
                                    // 隐式 Intent 一律兜住：个别 ROM 没有这个设置页
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }) { Text("去开启精确闹钟") }
                            }
                        }
                    }
                }
            }

            SectionLabel("隐藏的课程")
            SectionCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.hiddenCourses.isEmpty()) {
                        Text(
                            "暂无隐藏课程",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.hiddenCourses.forEach { course ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    course.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "· " + courseSourceLabel(course),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { restoreCourse(course) }) { Text("恢复") }
                            }
                        }
                        Text(
                            "自定义课 / 示例课需先恢复，才能在课表里删除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.hasSample) {
                SectionLabel("数据")
                SectionCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RowShape)
                            .clickable { viewModel.clearSampleData() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "清除示例课表",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "删除内置示例课程数据",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching { LocalDate.parse(firstMonday) }.getOrNull()
                ?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()
                        firstMonday = picked
                        saveSemesterInfo(firstMondayOverride = picked)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** 卡片内可点击行的圆角（与卡片自身圆角协调）。 */
private val RowShape = RoundedCornerShape(8.dp)

/** 分组标题（与「我的」页同款：小号主色标题）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 分组卡片（与「我的」页同款：surfaceVariant 底色）。 */
@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        content = content,
    )
}

/** 标题 + 副标题 + 开关的整行（toggleable 让整行成为唯一开关控件，TalkBack 只读一次）。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // onCheckedChange = null：整行是唯一的开关控件（见 toggleable），Switch 只负责显示
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 隐藏课程列表里的来源标注。 */
private fun courseSourceLabel(course: CourseEntity): String = when (course.source) {
    CourseEntity.SOURCE_IMPORT -> "教务"
    CourseEntity.SOURCE_SAMPLE -> "示例"
    else -> "自定义"
}

/** 通知是否被系统挡掉：应用级通知开关（含权限）被关，或「上课提醒」渠道被设为"关闭"。 */
private fun areNotificationsBlocked(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
    val channel = context.getSystemService(NotificationManager::class.java)
        .getNotificationChannel(ClassReminderScheduler.CHANNEL_ID)
    return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
}

/** 精确闹钟权限是否缺失（Android 12 起是独立开关，缺失时提醒会推迟到维护窗口才弹）。 */
private fun isExactAlarmBlocked(context: Context): Boolean =
    !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun openNotificationSettings(context: Context) {
    // 隐式 Intent 一律兜住：个别 ROM / 精简系统没有这个设置页
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 诊断文案："已排 N 个提醒 · 最近一次：明天 07:45"。 */
private fun reminderStatusText(info: ReminderScheduleInfo): String {
    val next = info.nextTriggerAtMillis ?: return "当前没有需要提醒的课（未开学 / 假期中 / 本学期已结束）"
    return "已排 ${info.scheduledCount} 个提醒 · 最近一次：${formatTrigger(next)}"
}

private fun formatTrigger(millis: Long): String {
    val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val hhmm = "%02d:%02d".format(dt.hour, dt.minute)
    val day = dt.toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> "今天 $hhmm"
        today.plusDays(1) -> "明天 $hhmm"
        else -> "${day.monthValue}月${day.dayOfMonth}日 $hhmm"
    }
}
