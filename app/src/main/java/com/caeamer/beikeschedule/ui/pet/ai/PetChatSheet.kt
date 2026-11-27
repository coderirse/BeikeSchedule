package com.caeamer.beikeschedule.ui.pet.ai

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 贴底用的滚动偏移上限：足够越过任意长度的最后一条，测量端会 clamp 到内容末尾。 */
private const val SCROLL_TO_END_OFFSET = 1_000_000

/** 滚到真正的底部：最后一条比视口高时也露出它的末尾，而不是把顶部锚在视口上沿。 */
private suspend fun LazyListState.snapToBottom() {
    val total = layoutInfo.totalItemsCount
    if (total > 0) scrollToItem(total - 1, SCROLL_TO_END_OFFSET)
}

private fun LazyListState.reachedBottom(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    return last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 8
}

/** 消息列表滚到尽头后不再把余速传给弹层：那是"滚动 ↔ 弹层高度"自激抖动的来源。 */
private val swallowSheetScroll = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetChatSheet(onDismiss: () -> Unit, onSettings: () -> Unit, vm: PetAiViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val runtime by vm.chat.collectAsStateWithLifecycle()
    val storageError by vm.storageError.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) vm.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.stop() }
    }
    var input by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val conversation = state?.conversation
    val messages = state?.messages?.filter { it.conversation == conversation }.orEmpty()
    // 用户主动翻旧消息后暂停跟随；松手回到底部或发送新消息时恢复
    var followBottom by remember(conversation) { mutableStateOf(true) }
    var initialJumpDone by remember(conversation) { mutableStateOf(false) }
    // 打开或切换会话时贴到最新；等布局完成再滚，避免空表或旧列表滚错位置
    LaunchedEffect(conversation, messages.isNotEmpty()) {
        if (conversation == null || messages.isEmpty() || initialJumpDone) return@LaunchedEffect
        repeat(3) {
            list.snapToBottom()
            withFrameNanos { }
            if (list.reachedBottom()) { initialJumpDone = true; return@LaunchedEffect }
        }
        initialJumpDone = true
    }
    LaunchedEffect(messages.size, runtime.text.length, followBottom) {
        if (followBottom) list.snapToBottom()
    }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followBottom = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> followBottom = list.reachedBottom()
            }
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.reachedBottom() }.collect { atBottom -> if (atBottom) followBottom = true }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        val density = LocalDensity.current
        val view = LocalView.current
        val bottomInset = maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
        var viewport by remember { mutableIntStateOf(0) }
        // 外层高度保持稳定，保证半屏/全屏锚点不随内容测量来回变化。
        // 内层仅占实际露出的高度，输入框与空状态操作不能落在屏幕之外。
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.96f).imePadding().onGloballyPositioned { coordinates ->
            val visible = (view.rootView.height - coordinates.positionInWindow().y.roundToInt() - bottomInset)
                .coerceIn(1, coordinates.size.height.coerceAtLeast(1))
            if (viewport != visible) viewport = visible
        }) {
        val height = if (viewport > 0) Modifier.height(with(density) { viewport.toDp() }) else Modifier.fillMaxHeight()
        // 键盘弹出或弹层展开/收起改变了视口高度：跟随状态下继续贴底，最新内容不被顶到折叠线外
        LaunchedEffect(viewport) { if (viewport > 0 && followBottom) list.snapToBottom() }
        Column(Modifier.fillMaxWidth().then(height).testTag("pet-chat")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(state?.petName ?: "课表小宠物", style = MaterialTheme.typography.titleMedium)
                    Text(state?.provider?.model ?: "尚未配置 AI", style = MaterialTheme.typography.labelSmall) }
                Box {
                    IconButton(onClick = { history = true }) { Icon(Icons.Default.History, "聊天历史") }
                    DropdownMenu(expanded = history, onDismissRequest = { history = false }) {
                        val conversations = state?.messages.orEmpty().filter { it.role == "user" }.distinctBy { it.conversation }.takeLast(30).reversed()
                        if (conversations.isEmpty()) DropdownMenuItem(text = { Text("还没有聊天记录") }, onClick = { history = false })
                        conversations.forEach { first -> DropdownMenuItem(text = { Text(first.text.take(28), maxLines = 1) }, onClick = { vm.openConversation(first.conversation); history = false }) }
                    }
                }
                IconButton(onClick = vm::newConversation) { Icon(Icons.Default.Add, "新聊天") }
                IconButton(onClick = { scope.launch { if (sheet.currentValue == SheetValue.Expanded) sheet.partialExpand() else sheet.expand() } }) {
                    Icon(if (sheet.currentValue == SheetValue.Expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (sheet.currentValue == SheetValue.Expanded) "收起聊天" else "展开聊天")
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭聊天") }
            }
            HorizontalDivider()
            if (state?.configured != true || storageError != null) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(storageError ?: "给宠物接上 AI，就能持续聊天、搜热点和分析学习情况。")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onSettings) { Text("去宠物设置") }
                }
            } else {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(swallowSheetScroll).testTag("pet-chat-messages"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (messages.isEmpty()) item {
                            Text("想聊点什么？", style = MaterialTheme.typography.titleLarge)
                            Text("可以聊日常，也可以问“下周忙不忙”“帮我分析成绩”。学习数据需先分项授权。", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onSettings) { Text("管理人格、记忆与授权") }
                        }
                        items(messages, key = { it.id }) { message -> ChatMessage(message) }
                        if (runtime.busy || runtime.text.isNotBlank()) item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (runtime.text.isNotBlank()) SelectionContainer { Text(runtime.text) }
                                if (runtime.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(runtime.status, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        runtime.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { followBottom = true; vm.retry() }, enabled = !runtime.busy) { Text("重试上一条") } } }
                    }
                }
                if (!runtime.busy && runtime.status.isNotBlank()) Text(runtime.status, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                if (!runtime.busy && runtime.status == "已停止，可重试") TextButton(onClick = { followBottom = true; vm.retry() }) { Text("重试上一条") }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it.take(6000) }, Modifier.weight(1f).testTag("pet-chat-input").onFocusChanged {
                        if (it.isFocused) scope.launch { sheet.expand() }
                    }, placeholder = { Text("和宠物聊聊…") }, maxLines = 4, enabled = !runtime.busy)
                    if (runtime.busy) FilledTonalButton(onClick = vm::stop) { Text("停止") }
                    else Button(onClick = { val text = input; input = ""; followBottom = true; vm.send(text) }, enabled = input.isNotBlank()) { Text("发送") }
                }
            }
        }
        }
    }
}

@Composable
private fun ChatMessage(message: AiMessage) {
    val context = LocalContext.current
    val own = message.role == "user"
    Column(Modifier.fillMaxWidth().background(if (own) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        MaterialTheme.shapes.large).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (own) "你" else "宠物", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        SelectionContainer { Text(message.text) }
        message.sources.forEach { source ->
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, source.url.toUri())) }
            }, contentPadding = PaddingValues(0.dp)) { Text("${source.title} · ${source.timeLabel}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
