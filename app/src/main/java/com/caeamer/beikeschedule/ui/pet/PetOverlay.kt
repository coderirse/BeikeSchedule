package com.caeamer.beikeschedule.ui.pet

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.caeamer.beikeschedule.ui.pet.ai.PetAiViewModel
import com.caeamer.beikeschedule.ui.pet.ai.hotspotAvailable
import java.time.LocalDateTime
import kotlin.math.abs
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

/** 翻周进出动画状态；弹层/退后台仍走整体卸载，不经过这里。 */
internal enum class PetPresence { VISIBLE, EXITING, AWAY, ENTERING }

@Composable
fun SchedulePetHost(paused: Boolean, pageTurning: Boolean = false, interactionEpoch: Int, environment: PetEnvironment = PetEnvironment(), suppressWelcome: Boolean = paused, viewModel: PetViewModel = viewModel(), onChat: (() -> Unit)? = null) {
    val positions = rememberSaveableStateHolder()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val settings = prefs ?: return
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState == Lifecycle.State.RESUMED) }
    DisposableEffect(owner, settings.enabled) {
        viewModel.visibility(settings.enabled && owner.lifecycle.currentState == Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState == Lifecycle.State.RESUMED
            // 快速 STARTED → RESUMED 也必须记录，不能等重组后才观察最终状态。
            viewModel.visibility(settings.enabled && resumed)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.visibility(false) }
    }
    // 翻周只暂缓显示；弹层才取消招呼，防止关弹层时重复欢迎。
    SideEffect { viewModel.interactionPaused = suppressWelcome; if (suppressWelcome) viewModel.welcome.value = null }
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
    val poses by produceState<PetPoseAtlas?>(null, appearance.id) {
        try { value = PetAtlasCache.loadPoses(context) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w("SchedulePet", "新姿态暂不可用，使用原动作", e) }
        catch (_: OutOfMemoryError) { Log.w("SchedulePet", "已跳过新姿态素材") }
    }
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    positions.SaveableStateProvider(appearance.id) {
        PetScene(image, poses, appearance, settings, facts, environment, interactionEpoch, pageTurning, viewModel, onChat)
    }
}

@Composable
private fun PetScene(atlas: ImageBitmap, poses: PetPoseAtlas?, appearance: PetAppearance, prefs: PetPreferences, facts: PetFacts?, environment: PetEnvironment, interactionEpoch: Int, pageTurning: Boolean, vm: PetViewModel, onChat: (() -> Unit)?, ai: PetAiViewModel = viewModel()) {
    val density = LocalDensity.current
    val currentFacts by rememberUpdatedState(facts)
    var bubble by remember { mutableStateOf<PetMessage?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var walking by remember { mutableStateOf(false) }
    var facingLeft by remember { mutableStateOf(false) }
    var posture by rememberSaveable { mutableStateOf(PetPosture.STANDING) }
    var placed by rememberSaveable { mutableStateOf(false) }
    var manuallyPlaced by rememberSaveable { mutableStateOf(false) }
    var quietUntil by remember { mutableLongStateOf(0L) }
    var normalizedX by rememberSaveable { mutableFloatStateOf(0.16f) }
    var normalizedY by rememberSaveable { mutableFloatStateOf(0.80f) }
    var nextCasual by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + prefs.frequency.delayMillis()) }
    var nextHotspot by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + Random.nextLong(90_000, 180_001)) }
    var lastTopic by remember { mutableStateOf<String?>(null) }
    var presence by remember { mutableStateOf(PetPresence.VISIBLE) }
    var animOffset by remember { mutableStateOf<Offset?>(null) }
    var animAlpha by remember { mutableFloatStateOf(1f) }
    val welcome by vm.welcome.collectAsStateWithLifecycle()
    val aiState by ai.state.collectAsStateWithLifecycle()
    val aiConfig = aiState?.requestConfig()
    val currentInteraction by rememberUpdatedState(interactionEpoch)
    val currentAiState by rememberUpdatedState(aiState)
    fun casualDelay() = if (aiState?.configured == true) aiState!!.proactiveMinutes * 60_000L else prefs.frequency.delayMillis()

    suspend fun casual(welcoming: Boolean = false, hotspot: Boolean = false): PetMessage {
        val line = try {
            if (aiState?.configured == true) {
                val state = aiState!!
                val topic = when {
                    welcoming -> "welcome"
                    hotspot -> "hotspot"
                    else -> proactiveTopic(lastTopic, hotspotAvailable(state), state.grants.isNotEmpty(), state.allowedMemories().isNotEmpty()).also { lastTopic = it }
                }
                val prompt = if (welcoming) "用户刚打开或切回课表，请对${prefs.address}自然地打个招呼。可按需看近期安排；不要每次都查新闻。" else proactivePrompt(topic, prefs.address)
                val recent = state.history().filter { it.role == "assistant" }.takeLast(6).map { it.text }
                var answer = withTimeoutOrNull(25_000) { ai.bubble(prompt) }
                if (answer != null && recent.any { tooSimilar(bubbleTrim(answer!!.text, 180), it) }) {
                    val retry = withTimeoutOrNull(25_000) { ai.bubble(prompt + "\n换完全不同的内容和说法，不要与最近说过的相似。") }
                    answer = if (retry != null && !recent.any { tooSimilar(bubbleTrim(retry.text, 180), it) } &&
                        !tooSimilar(bubbleTrim(retry.text, 180), bubbleTrim(answer!!.text, 180))) retry else null
                }
                answer?.let { PetMessage("ai:${SystemClock.elapsedRealtime()}", bubbleTrim(it.text, 180) + if (it.sources.isNotEmpty()) "（来源见聊天）" else "") }
            } else withTimeoutOrNull(1500) { vm.dialogue.next(PetDialogueRequest(prefs.address, vm.recentLines.toSet())) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val result = line
            ?: withTimeoutOrNull(1500) { vm.dialogue.next(PetDialogueRequest(prefs.address, vm.recentLines.toSet())) }
            ?: PetMessage("fallback", "今天也给你加油呀，慢慢来就好～")
        vm.rememberLine(result.id)
        return result
    }
    LaunchedEffect(interactionEpoch) {
        bubble = null
        quietUntil = SystemClock.elapsedRealtime() + 1500
    }
    LaunchedEffect(bubble) { if (bubble != null) { delay(8000); bubble = null } }
    LaunchedEffect(request, aiConfig) {
        if (request > 0) {
            bubble = casual()
            nextCasual = SystemClock.elapsedRealtime() + casualDelay()
        }
    }
    LaunchedEffect(welcome?.id) {
        val visit = welcome ?: return@LaunchedEffect
        delay(600)
        if (!dragging && bubble == null && presence == PetPresence.VISIBLE) {
            val important = currentFacts?.let { PetHints.resolve(it, LocalDateTime.now(), prefs) }?.firstOrNull { it.id !in vm.seenHints }
            if (important != null) bubble = important
            else {
                val epoch = currentInteraction
                val greeting = if (aiState?.configured == true) casual(welcoming = true)
                    else PetWelcomeLines.message(prefs.address, visit, LocalDateTime.now().hour, vm.recentLines.toSet()).also { vm.rememberLine(it.id) }
                if (!dragging && bubble == null && epoch == currentInteraction) bubble = greeting
            }
            important?.let { vm.rememberHint(it.id) }
        }
        vm.consumeWelcome(visit.id)
    }
    LaunchedEffect(prefs, aiConfig) {
        var casualJob: Job? = null
        nextCasual = SystemClock.elapsedRealtime() + casualDelay()
        while (isActive) {
            val tick = SystemClock.elapsedRealtime()
            if (!dragging && tick >= quietUntil && presence == PetPresence.VISIBLE) {
                val important = currentFacts?.let { PetHints.resolve(it, LocalDateTime.now(), prefs) }
                    ?.firstOrNull { it.id !in vm.seenHints }
                if (important != null && (bubble == null || bubble?.kind == PetMessageKind.CASUAL)) {
                    vm.rememberHint(important.id)
                    bubble = important
                } else if (bubble == null && prefs.chatter && casualJob?.isActive != true) {
                    val ai = currentAiState
                    val hotspotDue = ai != null && ai.configured && hotspotAvailable(ai) && tick >= nextHotspot
                    if (hotspotDue || tick >= nextCasual) {
                        val epoch = currentInteraction
                        casualJob = launch {
                            val message = casual(hotspot = hotspotDue)
                            if (bubble == null && !dragging && epoch == currentInteraction) bubble = message
                            val now = SystemClock.elapsedRealtime()
                            nextCasual = now + casualDelay()
                            if (hotspotDue) nextHotspot = now + ai!!.hotspotMinutes.coerceIn(5, 1440) * 60_000L
                        }
                    }
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
        val currentBubble by rememberUpdatedState(bubble)
        val currentQuietUntil by rememberUpdatedState(quietUntil)
        val currentPresence by rememberUpdatedState(presence)
        LaunchedEffect(environment, width, height) {
            if (presence != PetPresence.VISIBLE) return@LaunchedEffect
            if (!placed || (!manuallyPlaced && !PetSpace.free(currentPosition(), width, height, petSize, clearance, environment, posture))) {
                val stop = withContext(Dispatchers.Default) { PetSpace.choose(width, height, petSize, clearance, environment) }
                move(stop.position); posture = stop.posture; placed = true
            }
        }
        // 翻周：滑出淡出 → 等新周环境 → 从分界线/屏幕外走入；入场段只穿越保护带，落点必须空闲。
        LaunchedEffect(pageTurning, environment, width, height) {
            if (pageTurning) {
                if (presence == PetPresence.AWAY) return@LaunchedEffect
                presence = PetPresence.EXITING
                bubble = null
                val from = animOffset ?: currentPosition()
                val toEdge = if (from.x + petSize / 2 < width / 2) Offset(-petSize, from.y) else Offset(width, from.y)
                facingLeft = toEdge.x < from.x
                walking = true
                Animatable(0f).animateTo(1f, tween(220, easing = LinearEasing)) {
                    if (presence == PetPresence.EXITING && !dragging) {
                        animOffset = from + (toEdge - from) * value
                        animAlpha = 1f - value
                    }
                }
                walking = false
                if (presence != PetPresence.EXITING) return@LaunchedEffect
                animOffset = toEdge; animAlpha = 0f; presence = PetPresence.AWAY
            } else {
                if (presence == PetPresence.VISIBLE) return@LaunchedEffect
                presence = PetPresence.ENTERING
                bubble = null
                val stop = withContext(Dispatchers.Default) { PetSpace.choose(width, height, petSize, clearance, environment) }
                val target = stop.position
                fun landing(left: Boolean): Offset? {
                    val x = if (left) environment.sectionWidth else maxX
                    return (0..18).map { maxY * it / 18 }.sortedBy { abs(it - target.y) }
                        .firstNotNullOfOrNull { y -> Offset(x, y).takeIf { PetSpace.free(it, width, height, petSize, clearance, environment) } }
                }
                var left = target.x + petSize / 2 < width / 2
                var land = landing(left)
                if (land == null) { left = !left; land = landing(left) }
                if (land == null) {
                    move(target); posture = stop.posture; placed = true; manuallyPlaced = false
                    animOffset = null; animAlpha = 1f; presence = PetPresence.VISIBLE
                    return@LaunchedEffect
                }
                val origin = if (left) Offset(environment.sectionWidth - petSize, land.y) else Offset(width, land.y)
                val entryFrom = animOffset ?: origin
                animOffset = entryFrom; animAlpha = 1f
                facingLeft = land.x < entryFrom.x
                walking = true
                Animatable(0f).animateTo(1f, tween(240, easing = LinearEasing)) {
                    if (presence == PetPresence.ENTERING && !dragging) animOffset = entryFrom + (land - entryFrom) * value
                }
                var reached = land
                if (presence == PetPresence.ENTERING && !dragging) {
                    val path = withContext(Dispatchers.Default) { PetSpace.route(land, target, width, height, petSize, clearance, environment) }
                    for (point in path) {
                        val from = animOffset ?: currentPosition()
                        val duration = ((point - from).getDistance() / petSize * 1000).toInt().coerceIn(100, 2200)
                        facingLeft = point.x < from.x
                        Animatable(0f).animateTo(1f, tween(duration, easing = LinearEasing)) {
                            if (presence == PetPresence.ENTERING && !dragging) animOffset = from + (point - from) * value
                        }
                        if (presence != PetPresence.ENTERING || dragging) break
                        reached = point
                    }
                }
                if (presence == PetPresence.ENTERING && !dragging) {
                    move(reached)
                    posture = if (reached == target) stop.posture else PetPosture.STANDING
                    placed = true; manuallyPlaced = false
                }
                walking = false; animOffset = null; animAlpha = 1f; presence = PetPresence.VISIBLE
            }
        }
        LaunchedEffect(width, height, environment, dragging) {
            walking = false
            if (dragging) return@LaunchedEffect
            try {
                while (isActive) {
                    delay(Random.nextLong(6000, 14001))
                    if (currentBubble != null || currentPresence != PetPresence.VISIBLE || SystemClock.elapsedRealtime() < currentQuietUntil) continue
                    val here = currentPosition()
                    // 约 1/3 概率原地微动作：换姿态或转向，不位移
                    if (Random.nextInt(3) == 0) {
                        val options = buildList {
                            add(PetPosture.STANDING)
                            if (PetSpace.free(here, width, height, petSize, clearance, environment, PetPosture.PERCH)) add(PetPosture.PERCH)
                            if (here.x <= 0.5f && PetSpace.free(here, width, height, petSize, clearance, environment, PetPosture.LEAN_LEFT)) add(PetPosture.LEAN_LEFT)
                            if (here.x >= maxX - 0.5f && PetSpace.free(here, width, height, petSize, clearance, environment, PetPosture.LEAN_RIGHT)) add(PetPosture.LEAN_RIGHT)
                        }
                        posture = options.random()
                        if (Random.nextBoolean()) facingLeft = !facingLeft
                        continue
                    }
                    var walked = false
                    for (attempt in 0..1) {
                        if (walked) break
                        val start = currentPosition()
                        val stop = withContext(Dispatchers.Default) { PetSpace.choose(width, height, petSize, clearance, environment) }
                        val path = withContext(Dispatchers.Default) { PetSpace.route(start, stop.position, width, height, petSize, clearance, environment) }
                        if (path.isEmpty()) continue
                        facingLeft = stop.position.x < start.x
                        walking = true
                        posture = PetPosture.STANDING
                        var reached = start
                        for (point in path) {
                            if (currentPresence != PetPresence.VISIBLE) break
                            val from = currentPosition()
                            val duration = ((point - from).getDistance() / petSize * 1000).toInt().coerceIn(100, 2200)
                            facingLeft = point.x < from.x
                            Animatable(0f).animateTo(1f, tween(duration, easing = LinearEasing)) {
                                if (currentPresence == PetPresence.VISIBLE) move(from + (point - from) * value)
                            }
                            reached = point
                        }
                        walking = false
                        if (currentPresence == PetPresence.VISIBLE) {
                            move(reached)
                            posture = if (reached == stop.position) stop.posture else PetPosture.STANDING
                            manuallyPlaced = false
                        }
                        walked = true
                    }
                }
            } finally { walking = false }
        }
        val resting = !dragging && !walking && posture != PetPosture.STANDING && poses != null
        val bodyHeight = if (resting && posture == PetPosture.PERCH) petSize * 0.7f else petSize
        val row = when { dragging -> 4; walking -> if (facingLeft) 2 else 1; bubble != null -> 3; else -> 0 }
        val renderPosition = animOffset ?: currentPosition()
        val petModifier = Modifier
            .offset { IntOffset(renderPosition.x.roundToInt(), (renderPosition.y + petSize - bodyHeight).roundToInt()) }
            .size(with(density) { petSize.toDp() }, with(density) { bodyHeight.toDp() })
            .alpha(animAlpha)
            .testTag("schedule-pet")
            .semantics { contentDescription = "课表小宠物大肥鱼，点击说话，拖动移动" }
            .pointerInput(width, height, environment) {
                detectDragGestures(
                    onDragStart = {
                        // 动画中途被抓住：立即落位接管，状态机协程随后自行收尾
                        if (presence != PetPresence.VISIBLE) {
                            animOffset?.let { move(it) }
                            animOffset = null; animAlpha = 1f; presence = PetPresence.VISIBLE
                        }
                        dragging = true; bubble = null; posture = PetPosture.STANDING
                    },
                    onDragEnd = {
                        dragging = false; manuallyPlaced = true; quietUntil = SystemClock.elapsedRealtime() + 10000
                        val stop = PetSpace.destinations(width, height, petSize, clearance, environment)
                            .filter { it.posture != PetPosture.STANDING }.minByOrNull { (it.position - currentPosition()).getDistance() }
                        if (stop != null && (stop.position - currentPosition()).getDistance() < petSize / 3) {
                            move(stop.position); posture = stop.posture
                        }
                    },
                    onDragCancel = { dragging = false },
                ) { change, delta -> change.consume(); move(currentPosition() + delta) }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (onChat != null) onChat() else request++ }
        // 手势挂在稳定的父节点；站起/趴下换画布时不能中途丢掉本次拖动。
        Box(petModifier) {
            if (resting) PetRestSprite(poses!!, posture, Modifier.fillMaxSize())
            else PetSprite(atlas, row, appearance, Modifier.fillMaxSize())
        }
        bubble?.let { message ->
            var measuredHeight by remember { mutableIntStateOf(with(density) { 150.dp.roundToPx() }) }
            val bubbleWidth = with(density) { 208.dp.toPx() }.coerceAtMost(width)
            val p = currentPosition()
            val placement = remember(environment, p, bodyHeight, measuredHeight, bubbleWidth, width, height) {
                PetSpace.bubble(Rect(p.x, p.y + petSize - bodyHeight, p.x + petSize, p.y + petSize),
                    Size(bubbleWidth, measuredHeight.toFloat()), width, height, clearance, environment)
            }
            PetBubble(message, placement.side == PetTailSide.TOP, placement.anchor,
                Modifier.offset { IntOffset(placement.rect.left.roundToInt(), placement.rect.top.roundToInt()) }
                    .width(with(density) { bubbleWidth.toDp() }).onSizeChanged { measuredHeight = it.height },
                onDismiss = { bubble = null }, tailSide = placement.side, speaker = aiState?.petName ?: "大肥鱼",
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
internal fun PetRestSprite(atlas: PetPoseAtlas, posture: PetPosture, modifier: Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) { delay(if (frame == 0) 2800L else 160L); frame = 1 - frame }
    }
    Canvas(modifier) {
        val row = if (posture == PetPosture.PERCH) 0 else 1
        val region = atlas.regions[row]
        val scale = minOf(size.width / region.width, size.height / region.height)
        val drawn = IntSize((region.width * scale).roundToInt(), (region.height * scale).roundToInt())
        val x = if (posture == PetPosture.PERCH) (size.width - drawn.width) / 2 else 0f
        withTransform({ if (posture == PetPosture.LEAN_RIGHT) scale(-1f, 1f, center) }) {
            drawImage(atlas.image, srcOffset = IntOffset(frame * atlas.cellWidth + region.left, row * atlas.cellHeight + region.top),
                srcSize = IntSize(region.width, region.height), dstSize = drawn,
                dstOffset = IntOffset(x.roundToInt(), (size.height - drawn.height).roundToInt()), filterQuality = FilterQuality.Medium)
        }
    }
}

@Composable
internal fun PetBubble(message: PetMessage, below: Boolean, anchor: Float, modifier: Modifier, onDismiss: () -> Unit, tailSide: PetTailSide = if (below) PetTailSide.TOP else PetTailSide.BOTTOM, speaker: String = "大肥鱼") {
    val color = MaterialTheme.colorScheme.secondaryContainer
    Box(modifier.testTag("pet-bubble")) {
        Canvas(Modifier.matchParentSize()) {
            val gutter = 9.dp.toPx()
            val half = 8.dp.toPx()
            val horizontal = tailSide == PetTailSide.TOP || tailSide == PetTailSide.BOTTOM
            val edge = if (horizontal) size.width else size.height
            val a = (edge * anchor).coerceIn(half, (edge - half).coerceAtLeast(half))
            val path = Path().apply {
                when (tailSide) {
                    PetTailSide.TOP -> { moveTo(a, 0f); lineTo(a - half, gutter); lineTo(a + half, gutter) }
                    PetTailSide.BOTTOM -> { moveTo(a, size.height); lineTo(a - half, size.height - gutter); lineTo(a + half, size.height - gutter) }
                    PetTailSide.LEFT -> { moveTo(0f, a); lineTo(gutter, a - half); lineTo(gutter, a + half) }
                    PetTailSide.RIGHT -> { moveTo(size.width, a); lineTo(size.width - gutter, a - half); lineTo(size.width - gutter, a + half) }
                }
                close()
            }
            drawPath(path, color)
        }
        // 四边留白一致，换尾巴方向不改变文本宽高，避免尺寸回传与方位选择互相抖动。
        val gutter = PaddingValues(9.dp)
        // Material Surface 即使无 onClick 也有拦截命中的 pointerInput；浮动气泡必须仅绘制。
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSecondaryContainer) {
        Box(Modifier.padding(gutter).shadow(3.dp, MaterialTheme.shapes.large).background(color, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)) {
            Column(Modifier.padding(start = 14.dp, end = 10.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(when (message.kind) { PetMessageKind.COURSE -> "课前小提醒"; PetMessageKind.EXAM -> "考前加油站"; else -> "${speaker}说" },
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "关闭宠物气泡", Modifier.size(16.dp))
                    }
                }
                // 主体没有点击/拖动处理，不挡住下方课程；只有关闭按钮接收气泡操作。
                Text(message.text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
        }
    }
}
