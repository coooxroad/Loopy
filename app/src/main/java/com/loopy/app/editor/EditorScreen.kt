@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.loopy.app.editor

import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.loopy.app.macro.Macro
import com.loopy.app.macro.MacroStore
import com.loopy.app.macro.Stroke
import com.loopy.app.macro.TouchSample
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.RawRecorder
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.AnimatedBottomGradient
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.ui.theme.NeuBase
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

private val TraceStart = Color(0xFF3B82F6)
private val AddedGreen = Color(0xFF20C997)
private val AddedEnd = Color(0xFFF2FFFB)
private val TraceEnd = Color(0xFFEFF5FF)
private const val WINDOW_MS = 150L
private const val DP_PER_SEC = 68f

@Composable
fun MacroEditorScreen(macro: Macro, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val hasVideo = macro.videoPath != null

    // 편집 가능한 스트로크 상태
    val strokes = remember { mutableStateListOf<Stroke>().apply { addAll(macro.strokes) } }
    var selectedStroke by remember { mutableStateOf<Int?>(null) }
    fun persist() { MacroStore.updateStrokes(context, macro.id, strokes.toList()) }

    // UNDO / REDO 히스토리
    val undoStack = remember { mutableStateListOf<List<Stroke>>() }
    val redoStack = remember { mutableStateListOf<List<Stroke>>() }
    fun pushUndo() { undoStack.add(strokes.toList()); redoStack.clear() }
    fun applyStrokes(list: List<Stroke>) {
        strokes.clear(); strokes.addAll(list); persist()
    }
    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(strokes.toList())
        val prev = undoStack.removeAt(undoStack.size - 1)
        applyStrokes(prev); selectedStroke = null
    }
    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(strokes.toList())
        val next = redoStack.removeAt(redoStack.size - 1)
        applyStrokes(next); selectedStroke = null
    }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val macroDurationMs = (strokes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L).coerceAtLeast(1L)

    val player = remember(macro.videoPath) {
        if (!hasVideo) null else ExoPlayer.Builder(context).build().apply {
            val path = macro.videoPath!!
            val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
            setMediaItem(MediaItem.fromUri(uri)); prepare()
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    var playing by remember { mutableStateOf(false) }
    var timeEditing by remember { mutableStateOf(false) }
    var timeText by remember { mutableStateOf("0.00") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val timeFocus = remember { FocusRequester() }
    var positionMs by remember { mutableStateOf(0L) }
    var userScrubbing by remember { mutableStateOf(false) }

    val screenAspect = remember {
        val dm = context.resources.displayMetrics
        (dm.widthPixels.toFloat() / dm.heightPixels.toFloat()).coerceIn(0.2f, 2f)
    }
    var videoAspect by remember { mutableStateOf(screenAspect) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                if (vs.height > 0 && vs.width > 0) {
                    val par = if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f
                    videoAspect = (vs.width * par) / vs.height
                }
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener); player?.release() }
    }

    val contentAspect = if (hasVideo) videoAspect else screenAspect
    val totalMs: Long = run {
        val d = player?.duration ?: 0L
        if (hasVideo && d > 0) d else macroDurationMs + macro.videoOffsetMs
    }

    // 프리뷰 높이: 영상 종횡비에 맞춰 조정(가로 영상일 때 쪼그라들지 않게)
    val screenWDp = remember {
        val dm = context.resources.displayMetrics
        dm.widthPixels / dm.density
    }
    val maxPreviewDp = remember {
        val dm = context.resources.displayMetrics
        dm.heightPixels / dm.density * 0.46f
    }
    val previewH = run {
        val fit = screenWDp / contentAspect.coerceAtLeast(0.05f)
        fit.coerceAtMost(maxPreviewDp).dp
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (player != null) {
            while (playing) {
                positionMs = player.currentPosition
                if (player.playbackState == Player.STATE_ENDED) {
                    player.playWhenReady = false; playing = false; break
                }
                kotlinx.coroutines.delay(16)
            }
        } else {
            var last = System.currentTimeMillis()
            while (playing) {
                val now = System.currentTimeMillis()
                positionMs += (now - last); last = now
                if (positionMs >= totalMs) { positionMs = totalMs; playing = false; break }
                kotlinx.coroutines.delay(16)
            }
        }
    }

    fun togglePlay() {
        if (positionMs >= totalMs) { positionMs = 0L; player?.seekTo(0) }
        playing = !playing
        player?.playWhenReady = playing
    }
    fun seekTo(ms: Long) {
        positionMs = ms.coerceIn(0L, totalMs); player?.seekTo(positionMs)
        if (!timeEditing) timeText = fmtPlain(positionMs)
    }

    // 블록 선택 시: 재생헤드를 블록 가장 가까운 가장자리로 스냅(닿게)
    fun selectAndSnap(i: Int) {
        selectedStroke = i
        val s = strokes[i]
        val leftV = macro.videoOffsetMs + s.startMs
        val rightV = leftV + s.durationMs
        if (positionMs < leftV || positionMs > rightV) {
            val target = if (abs(positionMs - leftV) <= abs(positionMs - rightV)) leftV else rightV
            seekTo(target)
        }
    }


    LaunchedEffect(positionMs, timeEditing) {
        if (!timeEditing) timeText = fmtPlain(positionMs)
    }

    val playheadStrokeMs = positionMs - macro.videoOffsetMs
    // 현재 재생 시각의 화면 회전(녹화 중 기록된 회전 타임라인에서 조회)
    val currentRot = rotationAt(macro, playheadStrokeMs)

    // ── + 추가 촬영 (Shizuku /dev/input 캡처) ──
    val coScope = rememberCoroutineScope()
    val reader = remember { GeteventReader() }
    val recorder = remember { RawRecorder() }
    var capturing by remember { mutableStateOf(false) }
    var captureStarted by remember { mutableStateOf(false) }
    var captureMsg by remember { mutableStateOf("") }
    var captureBaseStrokeMs by remember { mutableStateOf(0L) }
    val liveTouches = remember { mutableStateMapOf<Int, Offset>() }

    fun startCapture() {
        val devs = runCatching { reader.probe() }.getOrDefault(emptyList())
        val dev = devs.firstOrNull { it.name.contains("touchscreen", true) } ?: devs.firstOrNull()
        if (dev == null) { captureMsg = "터치 디바이스를 못 찾음 (Shizuku 확인)"; capturing = true; return }
        captureBaseStrokeMs = playheadStrokeMs.coerceAtLeast(0L)
        recorder.reset()
        // 하단 저장/취소 버튼 영역(아래 12%)은 스트로크로 잡지 않음
        recorder.shouldIgnore = { _, v -> v > 0.88f }
        captureStarted = false
        captureMsg = "화면을 터치하면 촬영 시작"
        capturing = true
        playing = false; player?.playWhenReady = false; player?.seekTo(positionMs)
        reader.stream(coScope, listOf(dev)) { _, p ->
            if (!captureStarted && p.down) {
                captureStarted = true
                coScope.launch { player?.playWhenReady = true; playing = true }
            }
            coScope.launch {
                if (p.down) liveTouches[p.slot] = Offset(p.nx, p.ny) else liveTouches.remove(p.slot)
            }
            recorder.onPoint(p)
        }
    }
    fun finishCapture(save: Boolean) {
        reader.stop()
        playing = false; player?.playWhenReady = false
        if (save) {
            val snap = recorder.snapshot()
            if (snap.isNotEmpty()) {
                pushUndo()
                val curRot = currentRotation(context)
                for (s in snap) {
                    strokes.add(Stroke(captureBaseStrokeMs + s.startMs, s.durationMs, s.samples,
                        added = true, rotation = curRot))
                }
                persist()
            }
        }
        capturing = false; captureStarted = false; liveTouches.clear()
    }

    DisposableEffect(Unit) { onDispose { reader.stop() } }

    // 편집 연산 (재생헤드 기준)
    fun doDelete() {
        val i = selectedStroke ?: return
        pushUndo(); strokes.removeAt(i); selectedStroke = null; persist()
    }
    fun doTrimLeft() {
        val i = selectedStroke ?: return
        val r = trimLeft(strokes[i], playheadStrokeMs) ?: return
        pushUndo(); strokes[i] = r; persist()
    }
    fun doTrimRight() {
        val i = selectedStroke ?: return
        val r = trimRight(strokes[i], playheadStrokeMs) ?: return
        pushUndo(); strokes[i] = r; persist()
    }
    fun doSplit() {
        val i = selectedStroke ?: return
        val s = strokes[i]
        if (playheadStrokeMs <= s.startMs || playheadStrokeMs >= s.startMs + s.durationMs) return
        val pair = splitStroke(s, playheadStrokeMs) ?: return
        pushUndo(); strokes[i] = pair.first; strokes.add(i + 1, pair.second); persist()
    }
    fun doMove(i: Int, newStartMs: Long) {
        pushUndo(); strokes[i] = strokes[i].copy(startMs = newStartMs.coerceAtLeast(0L)); persist()
    }
    /** 박스 드래그 중: 실시간으로 스트로크를 평행이동(부드럽게 따라옴). */
    var nudging by remember { mutableStateOf(false) }
    fun nudgeLive(i: Int, dnx: Float, dny: Float) {
        if (dnx == 0f && dny == 0f) return
        if (!nudging) { pushUndo(); nudging = true } // 드래그 시작 시 한 번만 undo 기록
        val s = strokes[i]
        val moved = s.samples.map {
            it.copy(nx = (it.nx + dnx).coerceIn(0f, 1f), ny = (it.ny + dny).coerceIn(0f, 1f))
        }
        strokes[i] = s.copy(samples = moved)
    }
    /** 드래그 종료: 저장. */
    fun nudgeCommit() {
        if (!nudging) return
        nudging = false
        persist()
    }

    Box(Modifier.fillMaxSize().background(NeuBase)) {
        AnimatedBottomGradient()
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(LoopyCard)
                        .border(1.dp, CardStroke, CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", color = Accent, fontSize = 20.sp) }
                Text(
                    macro.name, color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(38.dp))
            }

            Box(
                Modifier.fillMaxWidth().height(previewH).background(Color(0xFF000000)),
                contentAlignment = Alignment.Center,
            ) {
                if (player != null) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                this.player = player
                                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        update = { it.player = if (capturing) null else player },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("영상 없음", color = Color(0xFF3A3F50), fontSize = 14.sp)
                }
                selectedStroke?.let { si ->
                    if (si < strokes.size) {
                        StrokeMoveBox(
                            stroke = strokes[si], contentAspect = contentAspect,
                            defaultRot = currentRot, screenAspect = screenAspect,
                            onDragDelta = { dnx, dny -> nudgeLive(si, dnx, dny) },
                            onDragEnd = { nudgeCommit() },
                        )
                    }
                }
                TraceOverlay(strokes, playheadStrokeMs, contentAspect, macro, currentRot, screenAspect)
            }

            // 인포바: 볼록 뉴모피즘 각진 직사각형
            Box(
                Modifier.fillMaxWidth().neuRaised().background(NeuBase)
                    .padding(horizontal = 18.dp, vertical = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // 시계: 파인(오목) 뉴모피즘. 탭하면 그 자리에서 자판 올라와 시간 직접 입력
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.neu(Color(0xFFE6EAF2), cornerDp = 9f, offDp = 2f, blurDp = 4.5f, raised = false)
                            .clip(RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        BasicTextField(
                            value = timeText,
                            onValueChange = { v ->
                                timeText = v
                                parseTime(v, totalMs)?.let { ms ->
                                    playing = false; player?.playWhenReady = false; seekTo(ms)
                                }
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = TextHi, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                parseTime(timeText, totalMs)?.let { ms ->
                                    playing = false; player?.playWhenReady = false; seekTo(ms)
                                }
                                focusManager.clearFocus()
                                keyboard?.hide()
                            }),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(TraceStart),
                            modifier = Modifier.width(52.dp)
                                .focusRequester(timeFocus)
                                .onFocusChanged { fs ->
                                    timeEditing = fs.isFocused
                                    if (fs.isFocused) timeText = fmtPlain(positionMs)
                                },
                        )
                    }
                    Text(" / ${fmt(totalMs)}", color = TextLo, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PlayPauseFilled(playing) { togglePlay() }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UndoRedoIcon(redo = false, enabled = undoStack.isNotEmpty()) { undo() }
                    Spacer(Modifier.width(4.dp))
                    UndoRedoIcon(redo = true, enabled = redoStack.isNotEmpty()) { redo() }
                }
            }

            // 편집공간(오목) + 타임라인
            Column(Modifier.fillMaxWidth().background(Color(0xFFE6EAF2))) {
                Box(
                    Modifier.fillMaxWidth().height(9.dp).background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFC9D0E0).copy(alpha = 0.55f), Color.Transparent),
                        ),
                    ),
                )
                Timeline(
                    strokes = strokes, totalMs = totalMs, positionMs = positionMs,
                    videoOffsetMs = macro.videoOffsetMs, videoPath = macro.videoPath,
                    density = density, selected = selectedStroke,
                    onScrubTime = { t -> playing = false; player?.playWhenReady = false; seekTo(t) },
                    onScrubbingChange = { userScrubbing = it }, userScrubbing = userScrubbing,
                    onSelect = { selectAndSnap(it) }, onMove = { i, ns -> doMove(i, ns) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 선택 시 편집 툴바 (뉴모피즘 버튼)
            if (selectedStroke != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditToolButton("trimLeft", "왼쪽 자르기", TextHi) { doTrimLeft() }
                    EditToolButton("split", "분할", TraceStart) { doSplit() }
                    EditToolButton("trimRight", "오른쪽 자르기", TextHi) { doTrimRight() }
                    EditToolButton("delete", "삭제", Color(0xFFE06A6A)) { doDelete() }
                }
            }

            // + 추가 촬영 버튼 (밝은 초록 뉴모피즘)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(56.dp)
                        .neu(NeuBase, fill = AddedGreen, cornerDp = 28f, offDp = 3f, blurDp = 7f)
                        .clickable { startCapture() },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(24.dp)) {
                        val c = Color.White; val sw = size.width * 0.13f
                        drawLine(c, Offset(size.width / 2f, size.height * 0.18f),
                            Offset(size.width / 2f, size.height * 0.82f), sw, cap = StrokeCap.Round)
                        drawLine(c, Offset(size.width * 0.18f, size.height / 2f),
                            Offset(size.width * 0.82f, size.height / 2f), sw, cap = StrokeCap.Round)
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }

        // 전체화면 캡처 오버레이
        if (capturing) {
            CaptureOverlay(
                player = player, message = captureMsg, started = captureStarted,
                live = liveTouches, rotationDeg = currentRotation(context), contentAspect = contentAspect,
                onSave = { finishCapture(true) }, onCancel = { finishCapture(false) },
            )
        }
    }
}

@Composable
private fun CaptureOverlay(
    player: ExoPlayer?, message: String, started: Boolean,
    live: Map<Int, Offset>, rotationDeg: Int, contentAspect: Float,
    onSave: () -> Unit, onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF000000))) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 회색 반투명 오버레이
        Box(Modifier.fillMaxSize().background(Color(0x59202020)))
        // 촬영 중 현재 터치 위치 실시간 트래킹(초록)
        Canvas(Modifier.fillMaxSize()) {
            if (live.isEmpty()) return@Canvas
            val bw = size.width; val bh = size.height
            val boxAspect = bw / bh
            val dispW: Float; val dispH: Float
            if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
            else { dispH = bh; dispW = bh * contentAspect }
            val offX = (bw - dispW) / 2f; val offY = (bh - dispH) / 2f
            for ((_, n) in live) {
                val (rx, ry) = rotNorm(n.x, n.y, rotationDeg)
                val p = Offset(offX + rx * dispW, offY + ry * dispH)
                drawTouchDot(p, 1f, AddedGreen, Triple(32, 201, 151))
            }
        }
        if (!started) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(message, color = Color(0xFFEFF1F6), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        // 하단 저장/취소
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CaptureButton("취소", Color(0xFF3A3F50), Color(0xFFCFD4E0)) { onCancel() }
                CaptureButton("저장", AddedGreen, Color.White) { onSave() }
            }
        }
    }
}

@Composable
private fun RowScope.CaptureButton(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(50.dp)
            .neu(Color(0xFF2A2E3A), fill = bg, cornerDp = 15f, offDp = 2.6f, blurDp = 6f)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RowScope.EditToolButton(kind: String, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(52.dp)
            .neu(NeuBase, cornerDp = 14f, offDp = 2.6f, blurDp = 5.5f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(20.dp)) { drawEditIcon(kind, tint) }
            Spacer(Modifier.height(3.dp))
            Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEditIcon(kind: String, col: Color) {
    val w = size.width; val h = size.height; val sw = w * 0.09f
    when (kind) {
        "trimLeft", "trimRight" -> {
            val left = kind == "trimLeft"
            val barX = if (left) w * 0.6f else w * 0.4f
            drawLine(col, Offset(barX, h * 0.16f), Offset(barX, h * 0.84f), sw, cap = StrokeCap.Round)
            val blkL = if (left) barX + w * 0.08f else w * 0.16f
            val blkR = if (left) w * 0.86f else barX - w * 0.08f
            drawRoundRect(col.copy(alpha = 0.9f), topLeft = Offset(blkL, h * 0.32f),
                size = Size(blkR - blkL, h * 0.36f), cornerRadius = CornerRadius(3f))
        }
        "split" -> {
            drawRoundRect(col, topLeft = Offset(w * 0.1f, h * 0.32f),
                size = Size(w * 0.3f, h * 0.36f), cornerRadius = CornerRadius(3f))
            drawRoundRect(col, topLeft = Offset(w * 0.6f, h * 0.32f),
                size = Size(w * 0.3f, h * 0.36f), cornerRadius = CornerRadius(3f))
            drawLine(col, Offset(w / 2f, h * 0.12f), Offset(w / 2f, h * 0.88f), sw * 0.9f, cap = StrokeCap.Round)
        }
        "delete" -> {
            drawLine(col, Offset(w * 0.18f, h * 0.28f), Offset(w * 0.82f, h * 0.28f), sw, cap = StrokeCap.Round)
            drawLine(col, Offset(w * 0.4f, h * 0.28f), Offset(w * 0.4f, h * 0.18f), sw, cap = StrokeCap.Round)
            drawLine(col, Offset(w * 0.4f, h * 0.18f), Offset(w * 0.6f, h * 0.18f), sw, cap = StrokeCap.Round)
            drawLine(col, Offset(w * 0.6f, h * 0.18f), Offset(w * 0.6f, h * 0.28f), sw, cap = StrokeCap.Round)
            drawRoundRect(col, topLeft = Offset(w * 0.26f, h * 0.34f),
                size = Size(w * 0.48f, h * 0.48f), cornerRadius = CornerRadius(4f),
                style = DrawStroke(width = sw))
            drawLine(col, Offset(w * 0.42f, h * 0.44f), Offset(w * 0.42f, h * 0.72f), sw * 0.8f, cap = StrokeCap.Round)
            drawLine(col, Offset(w * 0.58f, h * 0.44f), Offset(w * 0.58f, h * 0.72f), sw * 0.8f, cap = StrokeCap.Round)
        }
    }
}

/** UNDO(↶) / REDO(↷) 곡선 화살표 벡터. */
@Composable
private fun UndoRedoIcon(redo: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val col = if (enabled) Color(0xFF2B2D42) else Color(0xFFC2C6D2)
            val w = size.width; val h = size.height; val sw = w * 0.11f
            withTransform({ if (redo) scale(-1f, 1f, pivot = Offset(w / 2f, h / 2f)) }) {
                val cx = w / 2f; val cy = h * 0.52f; val r = w * 0.30f
                val startDeg = 30f; val sweep = 250f
                drawArc(col, startAngle = startDeg, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
                    style = DrawStroke(width = sw, cap = StrokeCap.Round))
                val endRad = Math.toRadians((startDeg + sweep).toDouble())
                val px = (cx + r * Math.cos(endRad)).toFloat()
                val py = (cy + r * Math.sin(endRad)).toFloat()
                val tx = (-Math.sin(endRad)).toFloat(); val ty = Math.cos(endRad).toFloat()
                val nx = Math.cos(endRad).toFloat(); val ny = Math.sin(endRad).toFloat()
                val len = w * 0.26f; val ww = w * 0.17f
                val tip = Offset(px + tx * len, py + ty * len)
                val b1 = Offset(px + nx * ww, py + ny * ww)
                val b2 = Offset(px - nx * ww, py - ny * ww)
                drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close() }, col)
            }
        }
    }
}

/** 색 조절 헬퍼. */
private fun Color.darker(f: Float = 0.86f) = Color(red * f, green * f, blue * f, alpha)
private fun Color.lighter(f: Float = 0.5f) =
    Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)

/**
 * 뉴모피즘: 광원은 왼쪽 위.
 *  - raised: 우하 어두운 그림자 + 좌상 흰 글로우 (요소 밖, 좁게)
 *  - recessed(파임): 안쪽 그림자 — 좌상 안쪽 어둡게, 우하 안쪽 밝게 (inner shadow)
 *  - 컬러 요소(fill)도 배경색 그림자/글로우를 받고 자기색 발광이 은은히 얹힘.
 */
private fun Modifier.neu(
    surface: Color,
    fill: Color? = null,
    cornerDp: Float = 12f,
    offDp: Float = 2.5f,
    blurDp: Float = 5f,
    raised: Boolean = true,
) = this.drawBehind {
    val off = offDp.dp.toPx()
    val blur = blurDp.dp.toPx()
    val cr = cornerDp.dp.toPx()
    val body = fill ?: surface
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)

    if (raised) {
        val shadowC = surface.darker(0.78f).toArgb()
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val pd = android.graphics.Paint()
            pd.isAntiAlias = true
            pd.color = body.toArgb()
            pd.setShadowLayer(blur, off, off, shadowC)
            fw.drawRoundRect(rect, cr, cr, pd)
            val pl = android.graphics.Paint()
            pl.isAntiAlias = true
            pl.color = body.toArgb()
            pl.setShadowLayer(blur, -off, -off, android.graphics.Color.WHITE)
            fw.drawRoundRect(rect, cr, cr, pl)
            if (fill != null) {
                val pg = android.graphics.Paint()
                pg.isAntiAlias = true
                pg.color = body.toArgb()
                pg.setShadowLayer(blur * 1.05f, 0f, off * 0.4f, fill.copy(alpha = 0.45f).toArgb())
                fw.drawRoundRect(rect, cr, cr, pg)
            }
        }
        // 안쪽 세로 그라데이션(위 밝게 → 아래 어둡게) + 상단 하이라이트
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(body.lighter(0.20f), body, body.darker(0.91f))),
            cornerRadius = CornerRadius(cr, cr),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.26f),
                0.32f to Color.Transparent,
            ),
            cornerRadius = CornerRadius(cr, cr),
        )
    } else {
        // 파임: 표면색으로 채우고 안쪽 그림자(좌상 어둡게, 우하 밝게)
        drawRoundRect(body, cornerRadius = CornerRadius(cr, cr))
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val sc = fw.save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(rect, cr, cr, android.graphics.Path.Direction.CW)
            }
            fw.clipPath(clipPath)
            // 좌상 안쪽 어두운 그림자
            val pIn = android.graphics.Paint()
            pIn.isAntiAlias = true
            pIn.style = android.graphics.Paint.Style.STROKE
            pIn.strokeWidth = off * 2.2f
            pIn.color = android.graphics.Color.TRANSPARENT
            pIn.setShadowLayer(blur, off, off, surface.darker(0.72f).toArgb())
            fw.drawRoundRect(
                android.graphics.RectF(-off * 2f, -off * 2f, size.width + off * 2f, size.height + off * 2f),
                cr, cr, pIn,
            )
            // 우하 안쪽 밝은 반사
            val pIn2 = android.graphics.Paint()
            pIn2.isAntiAlias = true
            pIn2.style = android.graphics.Paint.Style.STROKE
            pIn2.strokeWidth = off * 2.2f
            pIn2.color = android.graphics.Color.TRANSPARENT
            pIn2.setShadowLayer(blur, -off, -off, android.graphics.Color.WHITE)
            fw.drawRoundRect(
                android.graphics.RectF(-off * 2f, -off * 2f, size.width + off * 2f, size.height + off * 2f),
                cr, cr, pIn2,
            )
            fw.restoreToCount(sc)
        }
    }
}

/** 볼록 뉴모피즘(좌우 꽉 찬 바용): 수직 하이라이트/그림자. */
private fun Modifier.neuRaised() = this.drawBehind {
    val off = 3.dp.toPx(); val blur = 7.dp.toPx()
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
        val dark = android.graphics.Paint().apply {
            isAntiAlias = true; color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, 0f, off, 0xFFDDE2EB.toInt())
        }
        fw.drawRect(rect, dark)
        val light = android.graphics.Paint().apply {
            isAntiAlias = true; color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur * 0.7f, 0f, -off * 0.7f, 0x66FFFFFF)
        }
        fw.drawRect(rect, light)
    }
}

@Composable
private fun PlayPauseFilled(playing: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(19.dp)) {
            val c = Color(0xFF2B2D42)
            val w = size.width; val h = size.height
            if (playing) {
                val bw = w * 0.28f; val gap = w * 0.16f; val bh = h * 0.84f
                val top = (h - bh) / 2f
                val x1 = w / 2f - gap / 2f - bw; val x2 = w / 2f + gap / 2f
                val cr = CornerRadius(bw * 0.45f, bw * 0.45f)
                drawRoundRect(c, topLeft = Offset(x1, top), size = Size(bw, bh), cornerRadius = cr)
                drawRoundRect(c, topLeft = Offset(x2, top), size = Size(bw, bh), cornerRadius = cr)
            } else {
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.16f); lineTo(w * 0.84f, h * 0.5f)
                    lineTo(w * 0.24f, h * 0.84f); close()
                }
                drawPath(p, c)
                drawPath(p, c, style = DrawStroke(width = w * 0.16f, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun Timeline(
    strokes: List<Stroke>,
    totalMs: Long,
    positionMs: Long,
    videoOffsetMs: Long,
    videoPath: String?,
    density: androidx.compose.ui.unit.Density,
    selected: Int?,
    onScrubTime: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    userScrubbing: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var dpPerSec by remember { mutableStateOf(DP_PER_SEC) }
    val pxPerMs = with(density) { dpPerSec.dp.toPx() } / 1000f
    val thumbs = remember { mutableStateListOf<ImageBitmap?>() }
    val trackH = 52.dp
    val rulerH = 18.dp
    val cardVPad = 6.dp
    val cardH = trackH + cardVPad * 2
    val laneStep = 28.dp
    val blockH = 24.dp
    val thumbHpx = with(density) { trackH.toPx() }.toInt().coerceAtLeast(1)
    val secCount = ceil(totalMs / 1000f).toInt().coerceAtLeast(1)

    val lanes = assignLanes(strokes)
    val laneCount = (lanes.maxOfOrNull { it }?.plus(1) ?: 0).coerceAtLeast(1)
    val strokeTrackH = laneStep * laneCount
    val timelineH = 8.dp + rulerH + 4.dp + cardH + 6.dp + strokeTrackH + 10.dp

    // 드래그(홀드 이동) 상태
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragDx by remember { mutableStateOf(0f) }

    LaunchedEffect(videoPath, dpPerSec) {
        thumbs.clear()
        val path = videoPath ?: return@LaunchedEffect
        val secN = ceil(totalMs / 1000f).toInt().coerceAtLeast(1)
        withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            runCatching {
                val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
                r.setDataSource(context, uri)
                for (i in 0 until secN) {
                    val ib = runCatching {
                        val src = r.getFrameAtTime(i * 1000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        src?.let {
                            val ratio = thumbHpx.toFloat() / it.height
                            val w = (it.width * ratio).toInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(it, w, thumbHpx, true).asImageBitmap()
                        }
                    }.getOrNull()
                    withContext(Dispatchers.Main) { thumbs.add(ib) }
                }
            }
            runCatching { r.release() }
        }
    }

    LaunchedEffect(scrollState, dpPerSec) {
        snapshotFlow { scrollState.value to scrollState.isScrollInProgress }
            .collect { (px, inProgress) ->
                onScrubbingChange(inProgress)
                if (inProgress) onScrubTime((px / pxPerMs).toLong().coerceIn(0L, totalMs))
            }
    }
    LaunchedEffect(positionMs, userScrubbing, dpPerSec) {
        if (!userScrubbing) {
            val target = (positionMs * pxPerMs).toInt().coerceIn(0, scrollState.maxValue)
            if (abs(scrollState.value - target) > 1) runCatching { scrollState.scrollTo(target) }
        }
    }

    BoxWithConstraints(
        modifier.fillMaxWidth().height(timelineH)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val e = awaitPointerEvent()
                        if (e.changes.count { it.pressed } >= 2) {
                            val z = e.calculateZoom()
                            if (z != 1f) {
                                dpPerSec = (dpPerSec * z).coerceIn(24f, 240f)
                                e.changes.forEach { it.consume() }
                            }
                        }
                    } while (e.changes.any { it.pressed })
                }
            },
    ) {
        val viewportPx = constraints.maxWidth
        val halfPx = viewportPx / 2f
        val contentPx = viewportPx + totalMs * pxPerMs
        val contentDp = with(density) { contentPx.toDp() }
        val halfDp = with(density) { halfPx.toDp() }

        Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
            Column(Modifier.fillMaxWidth().horizontalScroll(scrollState).width(contentDp)) {
                Canvas(Modifier.fillMaxWidth().height(rulerH)) {
                    val yb = size.height
                    val txt = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(138, 141, 160)
                        textSize = 9.sp.toPx(); isAntiAlias = true
                    }
                    drawIntoCanvas { canvas ->
                        for (sec in 0..secCount) {
                            val x = halfPx + sec * 1000f * pxPerMs
                            drawLine(CardStroke, Offset(x, yb * 0.58f), Offset(x, yb), strokeWidth = 2f)
                            canvas.nativeCanvas.drawText("${sec}s", x + 3.dp.toPx(), yb * 0.5f, txt)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.height(cardH), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(halfDp))
                    Box(Modifier.height(cardH).clip(RoundedCornerShape(12.dp)).background(LoopyCard)) {
                        Row(Modifier.padding(vertical = cardVPad)) {
                            for (i in 0 until secCount) {
                                val ib = thumbs.getOrNull(i)
                                Box(
                                    Modifier.width(dpPerSec.dp).height(trackH)
                                        .background(Color(0xFF1A1D26)),
                                ) {
                                    if (ib != null) {
                                        Image(ib, contentDescription = null,
                                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(halfDp))
                }
                Spacer(Modifier.height(6.dp))
                // 스트로크 블록 트랙 (레인 배치, 영상 싱크, 홀드 드래그)
                Box(Modifier.fillMaxWidth().height(strokeTrackH)) {
                    for (i in strokes.indices) {
                        val s = strokes[i]
                        val lane = lanes[i]
                        val startVideoMs = videoOffsetMs + s.startMs
                        val dxDp = if (dragIndex == i) with(density) { dragDx.toDp() } else 0.dp
                        val xDp = halfDp + with(density) { (startVideoMs * pxPerMs).toDp() } + dxDp
                        val wDp = with(density) { (s.durationMs * pxPerMs).toDp() }.coerceAtLeast(12.dp)
                        val yDp = laneStep * lane + (laneStep - blockH) / 2
                        StrokeBlock(
                            selected = selected == i,
                            added = s.added,
                            onClick = { onSelect(i) },
                            zIndexValue = if (selected == i) 2f else 1f,
                            modifier = Modifier.offset(x = xDp, y = yDp).width(wDp).height(blockH)
                                .pointerInput(i, pxPerMs) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragIndex = i; dragDx = 0f },
                                        onDrag = { change, amount -> dragDx += amount.x; change.consume() },
                                        onDragEnd = {
                                            val ns = (s.startMs + (dragDx / pxPerMs)).toLong().coerceAtLeast(0L)
                                            onMove(i, ns); dragIndex = null; dragDx = 0f
                                        },
                                        onDragCancel = { dragIndex = null; dragDx = 0f },
                                    )
                                },
                        )
                    }
                }
            }
            // 중앙 재생헤드
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val phBot = with(density) { (rulerH + 4.dp + cardH + 6.dp + strokeTrackH + 4.dp).toPx() }
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.rgb(43, 45, 66)
                        strokeWidth = with(density) { 2.5.dp.toPx() }
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        setShadowLayer(with(density) { 5.dp.toPx() }, 0f,
                            with(density) { 1.dp.toPx() }, android.graphics.Color.argb(60, 0, 0, 0))
                    }
                    canvas.nativeCanvas.drawLine(x, 0f, x, phBot, paint)
                }
            }
        }
    }
}

/** 스트로크 블록. 그림자가 이웃 블록을 침범하지 않도록 안쪽 여백 안에서만 그린다. */
@Composable
private fun StrokeBlock(
    selected: Boolean, added: Boolean, zIndexValue: Float,
    onClick: () -> Unit, modifier: Modifier,
) {
    val shape = RoundedCornerShape(7.dp)
    val base = if (added) AddedGreen else TraceStart
    // zIndex로 선택 블록을 위로 → 그림자가 이웃에 가려지지 않고, 이웃을 덮지도 않음
    Box(modifier.zIndex(zIndexValue).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp)
                .clip(shape)
                .neu(NeuBase, fill = if (selected) Color.White else base,
                    cornerDp = 7f, offDp = 1.6f, blurDp = 3.2f)
                .then(if (selected) Modifier.border(2.dp, base, shape) else Modifier),
        )
    }
}

/**
 * 선택된 스트로크를 덮는 직사각형. 드래그하면 스트로크 전체가 평행이동.
 * 드래그 중엔 누적 오프셋만 들고 있다가(제스처 리셋 방지) 놓을 때 한 번에 적용.
 */
@Composable
private fun StrokeMoveBox(
    stroke: Stroke, contentAspect: Float, defaultRot: Int, screenAspect: Float,
    onDragDelta: (Float, Float) -> Unit, onDragEnd: () -> Unit,
) {
    val rotDeg = if (stroke.rotation >= 0) stroke.rotation else defaultRot
    Box(
        Modifier.fillMaxSize().pointerInput(rotDeg, contentAspect) {
            detectDragGestures(
                onDrag = { change, amount ->
                    change.consume()
                    val bw = size.width.toFloat(); val bh = size.height.toFloat()
                    val boxAspect = bw / bh
                    val dispW: Float; val dispH: Float
                    if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
                    else { dispH = bh; dispW = bh * contentAspect }
                    // 회전 구간이면 축소된 가로 영역 크기 기준으로 델타 환산
                    val land = rotDeg == 90 || rotDeg == 270
                    val landAsp = if (screenAspect > 0f) 1f / screenAspect else 16f / 9f
                    val aW: Float; val aH: Float
                    if (land) {
                        if (landAsp > (dispW / dispH)) { aW = dispW; aH = dispW / landAsp }
                        else { aH = dispH; aW = dispH * landAsp }
                    } else { aW = dispW; aH = dispH }
                    val sx = amount.x / aW; val sy = amount.y / aH
                    val (dnx, dny) = when (rotDeg) {
                        90 -> (-sy) to sx
                        180 -> (-sx) to (-sy)
                        270 -> sy to (-sx)
                        else -> sx to sy
                    }
                    onDragDelta(dnx, dny) // 실시간 반영 → 부드럽게 따라옴
                },
                onDragEnd = { onDragEnd() },
            )
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val bw = size.width; val bh = size.height
            val boxAspect = bw / bh
            val dispW: Float; val dispH: Float
            if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
            else { dispH = bh; dispW = bh * contentAspect }
            val offX = (bw - dispW) / 2f; val offY = (bh - dispH) / 2f
            if (stroke.samples.isEmpty()) return@Canvas
            // 회전 구간: 프레임 안의 축소된 가로 영역
            val landscape = rotDeg == 90 || rotDeg == 270
            val landAspect = if (screenAspect > 0f) 1f / screenAspect else 16f / 9f
            val areaW: Float; val areaH: Float; val areaX: Float; val areaY: Float
            if (landscape) {
                if (landAspect > (dispW / dispH)) { areaW = dispW; areaH = dispW / landAspect }
                else { areaH = dispH; areaW = dispH * landAspect }
                areaX = offX + (dispW - areaW) / 2f
                areaY = offY + (dispH - areaH) / 2f
            } else {
                areaW = dispW; areaH = dispH; areaX = offX; areaY = offY
            }
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (smp in stroke.samples) {
                val (rx, ry) = rotNorm(smp.nx, smp.ny, rotDeg)
                val x = areaX + rx * areaW; val y = areaY + ry * areaH
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            // 스트로크에 딱 맞게(아주 얇은 여유만) + 드래그 중 미리보기 이동
            val pad = 6f
            val l = minX - pad; val tp = minY - pad
            val w = (maxX - minX) + pad * 2; val h = (maxY - minY) + pad * 2
            val cr = 10f
            // 반투명 흰 채움(60%) — 뒤 화면 보임
            drawRoundRect(Color.White.copy(alpha = 0.6f), topLeft = Offset(l, tp), size = Size(w, h),
                cornerRadius = CornerRadius(cr, cr))
            // 테두리(키움) + 안쪽 얇은 밝은 라인으로 입체
            drawRoundRect(TraceStart, topLeft = Offset(l, tp), size = Size(w, h),
                cornerRadius = CornerRadius(cr, cr), style = DrawStroke(width = 3.5f))
            drawRoundRect(Color.White.copy(alpha = 0.55f), topLeft = Offset(l + 2.6f, tp + 2.6f),
                size = Size((w - 5.2f).coerceAtLeast(0f), (h - 5.2f).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(cr - 2f, cr - 2f), style = DrawStroke(width = 1.2f))
        }
    }
}

/** 매크로 시각 tMs에서의 화면 회전(녹화 중 기록된 타임라인 조회). */
private fun rotationAt(macro: Macro, tMs: Long): Int {
    val evts = macro.rotationEvents
    if (evts.isEmpty()) return macro.rotation
    var rot = evts.first().rotation
    for (e in evts) {
        if (e.tMs <= tMs) rot = e.rotation else break
    }
    return rot
}

/** 정규화 좌표를 화면 회전에 맞게 변환. */
private fun rotNorm(nx: Float, ny: Float, rotDeg: Int): Pair<Float, Float> = when (rotDeg) {
    90 -> ny to (1f - nx)
    180 -> (1f - nx) to (1f - ny)
    270 -> (1f - ny) to nx
    else -> nx to ny
}

/** 현재 화면 회전(0/90/180/270). */
private fun currentRotation(ctx: android.content.Context): Int {
    val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    @Suppress("DEPRECATION")
    return wm.defaultDisplay.rotation * 90
}

/** 현재 눌림 표시: 흰 점 + 강한 글로우 + 얇은 테두리(테두리→중심으로 아주 옅게 페이드). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTouchDot(
    p: Offset, edge: Float, ring: Color, rgb: Triple<Int, Int, Int>,
) {
    val r = 11f
    // 글로우(강화): 흰 코어 + 넓고 진한 컬러 글로우
    drawIntoCanvas { canvas ->
        val a = (255 * edge).toInt().coerceIn(0, 255)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            setShadowLayer(75f, 0f, 0f, android.graphics.Color.argb(a, rgb.first, rgb.second, rgb.third))
        }
        canvas.nativeCanvas.drawCircle(p.x, p.y, r, paint)
    }
    // 테두리 안쪽으로 아주 옅게 스며드는 그라데이션
    val rOuter = r + 3.5f
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.55f to ring.copy(alpha = 0.05f * edge),
                0.86f to ring.copy(alpha = 0.22f * edge),
                1.0f to ring.copy(alpha = 0.0f),
            ),
            center = p, radius = rOuter,
        ),
        radius = rOuter, center = p,
    )
    // 얇은 테두리 링
    drawCircle(ring.copy(alpha = 0.9f * edge), radius = r + 1.6f, center = p,
        style = DrawStroke(width = 1.8f))
}

@Composable
private fun TraceOverlay(
    strokes: List<Stroke>, playheadStrokeMs: Long, contentAspect: Float,
    macro: Macro, rotationDeg: Int, screenAspect: Float,
) {
    Canvas(Modifier.fillMaxSize()) {
        val bw = size.width; val bh = size.height
        val boxAspect = bw / bh
        // 1) 영상 프레임이 화면에 그려지는 사각형(letterbox 보정)
        val dispW: Float; val dispH: Float
        if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
        else { dispH = bh; dispW = bh * contentAspect }
        val offX = (bw - dispW) / 2f; val offY = (bh - dispH) / 2f

        /**
         * 2) 회전 구간 보정.
         * 화면녹화 영상은 프레임 크기가 고정(세로)이라, 기기를 돌리면 가로 화면이
         * 그 세로 프레임 '안에' 폭 맞춤으로 축소되어 세로 중앙에 배치된다.
         * 따라서 회전(90/270) 구간의 스트로크는 프레임 전체가 아니라
         * 그 축소된 가로 영역에 매핑해야 한다.
         */
        fun mapPt(nx: Float, ny: Float, rotDeg: Int): Offset {
            val (rx, ry) = rotNorm(nx, ny, rotDeg)
            val landscape = rotDeg == 90 || rotDeg == 270
            if (!landscape) {
                return Offset(offX + rx * dispW, offY + ry * dispH)
            }
            // 가로 화면의 종횡비 = 1/screenAspect (세로화면 비의 역수)
            val landAspect = if (screenAspect > 0f) 1f / screenAspect else 16f / 9f
            // 프레임(dispW x dispH) 안에 가로 화면을 fit
            val innerW: Float; val innerH: Float
            if (landAspect > (dispW / dispH)) { innerW = dispW; innerH = dispW / landAspect }
            else { innerH = dispH; innerW = dispH * landAspect }
            val inX = offX + (dispW - innerW) / 2f
            val inY = offY + (dispH - innerH) / 2f
            return Offset(inX + rx * innerW, inY + ry * innerH)
        }
        for (s in strokes) {
            val relT = playheadStrokeMs - s.startMs
            if (relT < -WINDOW_MS || relT > s.durationMs + WINDOW_MS) continue
            val edge = when {
                relT < 0 -> 1f + relT / WINDOW_MS.toFloat()
                relT > s.durationMs -> 1f - (relT - s.durationMs) / WINDOW_MS.toFloat()
                else -> 1f
            }.coerceIn(0f, 1f)
            if (edge <= 0f) continue
            val samples = s.samples
            if (samples.isEmpty()) continue
            val sRot = if (s.rotation >= 0) s.rotation else rotationAt(macro, s.startMs)
            val cStart = if (s.added) AddedGreen else TraceStart
            val cEnd = if (s.added) AddedEnd else TraceEnd
            val rgb = if (s.added) Triple(32, 201, 151) else Triple(59, 130, 246)
            val revealT = relT.coerceIn(0L, s.durationMs)
            val dur = s.durationMs.coerceAtLeast(1L)
            val pressing = relT in 0..s.durationMs
            var prev: Offset? = null; var prevFrac = 0f
            for (smp in samples) {
                if (smp.t > revealT + 40L) break
                val p = mapPt(smp.nx, smp.ny, sRot)
                val frac = (smp.t.toFloat() / dur).coerceIn(0f, 1f)
                if (prev != null) {
                    val c = lerp(cStart, cEnd, (prevFrac + frac) / 2f)
                        .copy(alpha = edge * (if (pressing) 1f else 0.5f))
                    drawLine(c, prev, p, strokeWidth = if (pressing) 9f else 6f)
                }
                prev = p; prevFrac = frac
            }
            val cur = sampleAt(samples, revealT)
            if (cur != null) {
                val p = mapPt(cur.first, cur.second, sRot)
                if (pressing) drawTouchDot(p, edge, cStart, rgb)
                else drawCircle(cEnd.copy(alpha = 0.4f * edge), radius = 6f, center = p)
            }
        }
    }
}

// ── 스트로크 편집 연산 ──
private fun trimLeft(s: Stroke, atStrokeMs: Long): Stroke? {
    val rel = (atStrokeMs - s.startMs).coerceIn(0L, s.durationMs)
    val kept = s.samples.filter { it.t >= rel }.map { it.copy(t = it.t - rel) }
    if (kept.size < 2) return null
    return Stroke(s.startMs + rel, s.durationMs - rel, kept)
}
private fun trimRight(s: Stroke, atStrokeMs: Long): Stroke? {
    val rel = (atStrokeMs - s.startMs).coerceIn(0L, s.durationMs)
    val kept = s.samples.filter { it.t <= rel }
    if (kept.size < 2) return null
    return Stroke(s.startMs, rel, kept)
}
private fun splitStroke(s: Stroke, atStrokeMs: Long): Pair<Stroke, Stroke>? {
    val left = trimRight(s, atStrokeMs) ?: return null
    val right = trimLeft(s, atStrokeMs) ?: return null
    return left to right
}

/** 각 스트로크의 레인 번호(시간 겹치면 다음 레인). */
private fun assignLanes(strokes: List<Stroke>): List<Int> {
    val result = IntArray(strokes.size)
    val laneEnd = ArrayList<Long>()
    for (idx in strokes.indices.sortedBy { strokes[it].startMs }) {
        val s = strokes[idx]
        var lane = laneEnd.indexOfFirst { it <= s.startMs }
        if (lane < 0) { lane = laneEnd.size; laneEnd.add(0L) }
        laneEnd[lane] = s.startMs + s.durationMs
        result[idx] = lane
    }
    return result.toList()
}

private fun sampleAt(samples: List<TouchSample>, t: Long): Pair<Float, Float>? {
    if (samples.isEmpty()) return null
    if (t <= samples.first().t) return samples.first().nx to samples.first().ny
    if (t >= samples.last().t) return samples.last().nx to samples.last().ny
    for (i in 1 until samples.size) {
        val a = samples[i - 1]; val b = samples[i]
        if (t in a.t..b.t) {
            val fr = if (b.t == a.t) 0f else (t - a.t).toFloat() / (b.t - a.t)
            return (a.nx + (b.nx - a.nx) * fr) to (a.ny + (b.ny - a.ny) * fr)
        }
    }
    return samples.last().nx to samples.last().ny
}

private fun fmt(ms: Long): String {
    val s = ms / 1000; return "%d:%02d.%02d".format(s / 60, s % 60, (ms % 1000) / 10)
}

/** 초 단위 평문(입력 필드 기본값). */
private fun fmtPlain(ms: Long): String = "%.2f".format(ms / 1000.0)

/** "6.04" 또는 "1:06.04" → ms. */
private fun parseTime(s: String, total: Long): Long? {
    val str = s.trim()
    if (str.isEmpty()) return null
    return runCatching {
        if (str.contains(":")) {
            val parts = str.split(":")
            val m = parts[0].trim().toLong()
            val sec = parts[1].trim().toDouble()
            m * 60_000L + (sec * 1000).toLong()
        } else {
            (str.toDouble() * 1000).toLong()
        }
    }.getOrNull()?.coerceIn(0L, total)
}
