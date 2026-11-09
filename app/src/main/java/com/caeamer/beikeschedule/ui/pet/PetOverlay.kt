package com.caeamer.beikeschedule.ui.pet

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/** 坐标限于内容区，且避开右下角的原有添加按钮；拖动和随机移动共用。 */
internal fun safePetPosition(point: Offset, width: Float, height: Float, size: Float, clearance: Float): Offset {
    val x = point.x.coerceIn(0f, (width - size).coerceAtLeast(0f))
    var y = point.y.coerceIn(0f, (height - size).coerceAtLeast(0f))
    if (x + size > width - clearance && y + size > height - clearance) {
        y = (height - clearance - size).coerceAtLeast(0f)
    }
    return Offset(x, y)
}

@Composable
fun SchedulePetHost(paused: Boolean, interactionEpoch: Int, viewModel: PetViewModel = viewModel()) {
    val positions = rememberSaveableStateHolder()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState == Lifecycle.State.RESUMED) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState == Lifecycle.State.RESUMED }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val settings = prefs ?: return
    if (!settings.enabled || paused || !resumed) return
    val context = LocalContext.current.applicationContext
    val appearance = PetAppearances.available.first()
    val atlas by produceState<ImageBitmap?>(null, appearance.id) {
        try { value = PetAtlasCache.load(context, appearance) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("SchedulePet", "宠物素材暂不可用", e) }
        catch (_: OutOfMemoryError) { Log.w("SchedulePet", "内存不足，已跳过宠物素材") }
    }
    val image = atlas ?: return
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    positions.SaveableStateProvider(appearance.id) {
        PetScene(image, appearance, settings, facts, interactionEpoch, viewModel)
    }
}

@Composable
private fun PetScene(atlas: ImageBitmap, appearance: PetAppearance, prefs: PetPreferences, facts: PetFacts?, interactionEpoch: Int, vm: PetViewModel) {
    val density = LocalDensity.current
    val currentFacts by rememberUpdatedState(facts)
    var bubble by remember { mutableStateOf<PetMessage?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var walking by remember { mutableStateOf(false) }
    var facingLeft by remember { mutableStateOf(false) }
    var quietUntil by remember { mutableLongStateOf(0L) }
    var normalizedX by rememberSaveable { mutableFloatStateOf(0.16f) }
    var normalizedY by rememberSaveable { mutableFloatStateOf(0.80f) }
    var nextCasual by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + prefs.frequency.delayMillis()) }

    suspend fun casual(): PetMessage {
        val line = try {
            withTimeoutOrNull(1500) { vm.dialogue.next(PetDialogueRequest(prefs.address, vm.recentLines.toSet())) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val result = line ?: PetMessage("fallback", "今天也给你加油呀，慢慢来就好～")
        vm.rememberLine(result.id)
        return result
    }
    LaunchedEffect(interactionEpoch) {
        bubble = null
        quietUntil = SystemClock.elapsedRealtime() + 1500
    }
    LaunchedEffect(bubble) { if (bubble != null) { delay(8000); bubble = null } }
    LaunchedEffect(request) {
        if (request > 0) {
            bubble = casual()
            nextCasual = SystemClock.elapsedRealtime() + prefs.frequency.delayMillis()
        }
    }
    LaunchedEffect(prefs) {
        nextCasual = SystemClock.elapsedRealtime() + prefs.frequency.delayMillis()
        bubble = null
        delay(2000)
        if (!vm.greeted && prefs.chatter && currentFacts != null && bubble == null && SystemClock.elapsedRealtime() >= quietUntil) {
            vm.greeted = true
            bubble = PetMessage("hello", "${prefs.address.takeIf(String::isNotBlank)?.let { "$it，" }.orEmpty()}我来陪你看课表啦！点点我可以说话，拖一拖就能搬家～")
        }
        while (isActive) {
            val tick = SystemClock.elapsedRealtime()
            if (!dragging && tick >= quietUntil) {
                val important = currentFacts?.let { PetHints.resolve(it, LocalDateTime.now(), prefs) }
                    ?.firstOrNull { it.id !in vm.seenHints }
                if (important != null && (bubble == null || bubble?.kind == PetMessageKind.CASUAL)) {
                    vm.rememberHint(important.id)
                    bubble = important
                } else if (bubble == null && prefs.chatter && tick >= nextCasual) {
                    bubble = casual()
                    nextCasual = tick + prefs.frequency.delayMillis()
                }
            }
            delay(5000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val petSize = with(density) { 64.dp.toPx() }.coerceAtMost(width).coerceAtMost(height)
        val clearance = with(density) { 80.dp.toPx() }
        if (width <= 0 || height <= 0 || petSize <= 0) return@BoxWithConstraints
        val maxX = (width - petSize).coerceAtLeast(1f)
        val maxY = (height - petSize).coerceAtLeast(1f)
        fun currentPosition() = safePetPosition(Offset(normalizedX * maxX, normalizedY * maxY), width, height, petSize, clearance)
        fun move(point: Offset) {
            val p = safePetPosition(point, width, height, petSize, clearance)
            normalizedX = p.x / maxX
            normalizedY = p.y / maxY
        }
        LaunchedEffect(width, height, dragging, bubble, interactionEpoch) {
            walking = false
            if (dragging || bubble != null) return@LaunchedEffect
            try {
                while (isActive) {
                    delay(Random.nextLong(6000, 15000))
                    if (SystemClock.elapsedRealtime() < quietUntil) continue
                    val start = currentPosition()
                    val target = safePetPosition(Offset(Random.nextFloat() * maxX, Random.nextFloat() * maxY), width, height, petSize, clearance)
                    facingLeft = target.x < start.x
                    walking = true
                    Animatable(0f).animateTo(1f, tween(4000, easing = LinearEasing)) {
                        move(Offset(start.x + (target.x - start.x) * value, start.y + (target.y - start.y) * value))
                    }
                    walking = false
                }
            } finally { walking = false }
        }
        val row = when { dragging -> 4; walking -> if (facingLeft) 2 else 1; bubble != null -> 3; else -> 0 }
        PetSprite(atlas, row, appearance, Modifier
            .offset { val p = currentPosition(); IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
            .size(with(density) { petSize.toDp() })
            .testTag("schedule-pet")
            .semantics { contentDescription = "课表小宠物大肥鱼，点击说话，拖动移动" }
            .pointerInput(width, height) {
                detectDragGestures(
                    onDragStart = { dragging = true; bubble = null },
                    onDragEnd = { dragging = false; quietUntil = SystemClock.elapsedRealtime() + 6000 },
                    onDragCancel = { dragging = false },
                ) { change, delta -> change.consume(); move(currentPosition() + delta) }
            }
            .clickable { request++ },
        )
        bubble?.let { message ->
            var measuredHeight by remember { mutableIntStateOf(with(density) { 150.dp.roundToPx() }) }
            val bubbleWidth = with(density) { 236.dp.toPx() }.coerceAtMost(width)
            val gap = with(density) { 4.dp.toPx() }
            val p = currentPosition()
            val below = p.y < measuredHeight + gap
            val bubbleX = (p.x + petSize / 2 - bubbleWidth / 2).coerceIn(0f, (width - bubbleWidth).coerceAtLeast(0f))
            val bubbleY = (if (below) p.y + petSize + gap else p.y - measuredHeight - gap)
                .coerceIn(0f, (height - measuredHeight).coerceAtLeast(0f))
            PetBubble(message, below, (p.x + petSize / 2 - bubbleX) / bubbleWidth,
                Modifier.offset { IntOffset(bubbleX.roundToInt(), bubbleY.roundToInt()) }
                    .width(with(density) { bubbleWidth.toDp() }).onSizeChanged { measuredHeight = it.height },
                onDismiss = { bubble = null },
            )
        }
    }
}

@Composable
private fun PetSprite(atlas: ImageBitmap, row: Int, appearance: PetAppearance, modifier: Modifier) {
    val count = when (row) { 0 -> 6; 3 -> 4; 4 -> 5; else -> 8 }
    var frame by remember(row) { mutableIntStateOf(0) }
    LaunchedEffect(row) {
        while (isActive) {
            delay(if (row == 0) listOf(1680L, 660L, 660L, 840L, 840L, 1920L)[frame % count] else 140L)
            frame = (frame + 1) % count
        }
    }
    Canvas(modifier) {
        val cellWidth = atlas.width / appearance.columns
        val cellHeight = atlas.height / appearance.rows
        val scale = minOf(size.width / cellWidth, size.height / cellHeight)
        val drawn = IntSize((cellWidth * scale).roundToInt(), (cellHeight * scale).roundToInt())
        drawImage(atlas, srcOffset = IntOffset(frame % count * cellWidth, row * cellHeight),
            srcSize = IntSize(cellWidth, cellHeight), dstSize = drawn,
            dstOffset = IntOffset(((size.width - drawn.width) / 2).roundToInt(), ((size.height - drawn.height) / 2).roundToInt()),
            filterQuality = FilterQuality.Medium)
    }
}

@Composable
private fun PetBubble(message: PetMessage, below: Boolean, anchor: Float, modifier: Modifier, onDismiss: () -> Unit) {
    val color = MaterialTheme.colorScheme.secondaryContainer
    @Composable fun Tail() {
        Canvas(Modifier.fillMaxWidth().height(9.dp)) {
            val x = (size.width * anchor).coerceIn(12.dp.toPx(), (size.width - 12.dp.toPx()).coerceAtLeast(12.dp.toPx()))
            val path = Path().apply {
                moveTo(x, if (below) 0f else size.height)
                lineTo(x - 8.dp.toPx(), if (below) size.height else 0f)
                lineTo(x + 8.dp.toPx(), if (below) size.height else 0f); close()
            }
            drawPath(path, color)
        }
    }
    Column(modifier.testTag("pet-bubble")) {
        if (below) Tail()
        Surface(color = color, shape = MaterialTheme.shapes.large, shadowElevation = 3.dp) {
            Column(Modifier.padding(start = 14.dp, end = 10.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(when (message.kind) { PetMessageKind.COURSE -> "课前小提醒"; PetMessageKind.EXAM -> "考前加油站"; else -> "大肥鱼说" },
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "关闭宠物气泡", Modifier.size(16.dp))
                    }
                }
                // 主体没有点击/拖动处理，不挡住下方课程；只有关闭按钮接收气泡操作。
                Text(message.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!below) Tail()
    }
}
