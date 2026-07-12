@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.loopy.app.editor

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import com.loopy.app.core.io.ShizukuIo
import com.loopy.app.core.stroke.StrokeOps
import com.loopy.app.input.RawRecorder
import com.loopy.app.macro.Macro
import com.loopy.app.macro.MacroStore
import com.loopy.app.macro.Stroke
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.AnimatedBottomGradient
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.ui.theme.NeuBase
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo
import com.loopy.app.ui.theme.neu
import com.loopy.app.ui.theme.neuBar
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun MacroEditorScreen(macro: Macro, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coScope = rememberCoroutineScope()
    val io = remember { ShizukuIo(context, coScope) }
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

    // 터치 좌표는 네비게이션 바를 포함한 실제 화면 기준이므로, 종횡비도 같은 기준이어야 한다.
    val screenAspect = remember {
        val (w, h) = io.screenSize()
        (w.toFloat() / h.toFloat()).coerceIn(0.2f, 2f)
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
    val currentRot = StrokeOps.rotationAt(macro, playheadStrokeMs)

    // ── + 추가 촬영 (Shizuku /dev/input 캡처) ──
    val recorder = remember { RawRecorder() }
    var capturing by remember { mutableStateOf(false) }
    var captureStarted by remember { mutableStateOf(false) }
    var captureMsg by remember { mutableStateOf("") }
    var captureBaseStrokeMs by remember { mutableStateOf(0L) }
    val liveTouches = remember { mutableStateMapOf<Int, Offset>() }

    fun startCapture() {
        if (!io.available) {
            captureMsg = "Shizuku 연결 필요"
            capturing = true
            return
        }
        captureBaseStrokeMs = playheadStrokeMs.coerceAtLeast(0L)
        recorder.reset()
        // 저장/취소 버튼이 놓인 하단은 스트로크로 잡히면 안 된다.
        recorder.shouldIgnore = { _, v -> v > 0.88f }
        captureStarted = false
        captureMsg = "화면을 눌러 시작"
        capturing = true
        playing = false
        player?.playWhenReady = false
        player?.seekTo(positionMs)

        io.startCapture { slot, nx, ny, down ->
            // 영상은 멈춰 있다가 첫 터치와 함께 흐른다. 손이 닿는 순간을 기준으로 맞추기 위해서다.
            if (!captureStarted && down) {
                captureStarted = true
                coScope.launch {
                    player?.playWhenReady = true
                    playing = true
                }
            }
            coScope.launch {
                if (down) liveTouches[slot] = Offset(nx, ny) else liveTouches.remove(slot)
            }
            recorder.onPoint(slot, nx, ny, down)
        }
    }
    fun finishCapture(save: Boolean) {
        io.stopCapture()
        playing = false; player?.playWhenReady = false
        if (save) {
            val snap = recorder.snapshot()
            if (snap.isNotEmpty()) {
                pushUndo()
                val curRot = io.rotation()
                for (s in snap) {
                    strokes.add(Stroke(captureBaseStrokeMs + s.startMs, s.durationMs, s.samples,
                        added = true, rotation = curRot))
                }
                persist()
            }
        }
        capturing = false; captureStarted = false; liveTouches.clear()
    }

    DisposableEffect(Unit) { onDispose { io.stopCapture() } }

    // 편집 연산 (재생헤드 기준)
    fun doDelete() {
        val i = selectedStroke ?: return
        pushUndo(); strokes.removeAt(i); selectedStroke = null; persist()
    }
    fun doTrimLeft() {
        val i = selectedStroke ?: return
        val r = StrokeOps.trimLeft(strokes[i], playheadStrokeMs) ?: return
        pushUndo(); strokes[i] = r; persist()
    }
    fun doTrimRight() {
        val i = selectedStroke ?: return
        val r = StrokeOps.trimRight(strokes[i], playheadStrokeMs) ?: return
        pushUndo(); strokes[i] = r; persist()
    }
    fun doSplit() {
        val i = selectedStroke ?: return
        val s = strokes[i]
        if (playheadStrokeMs <= s.startMs || playheadStrokeMs >= s.startMs + s.durationMs) return
        val pair = StrokeOps.split(s, playheadStrokeMs) ?: return
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
        strokes[i] = StrokeOps.move(strokes[i], dnx, dny)
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
                Modifier.fillMaxWidth().neuBar().background(NeuBase)
                    .padding(horizontal = 18.dp, vertical = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // 시계: 파인(오목) 뉴모피즘. 탭하면 그 자리에서 자판 올라와 시간 직접 입력
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.neu(Color(0xFFE6EAF2), corner = 9.dp, offset = 2.dp, blur = 4.5.dp, raised = false)
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
                        .neu(NeuBase, fill = AddedGreen, corner = 28.dp, offset = 3.dp, blur = 7.dp)
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
                live = liveTouches, rotationDeg = io.rotation(), contentAspect = contentAspect,
                onSave = { finishCapture(true) }, onCancel = { finishCapture(false) },
            )
        }
    }
}

