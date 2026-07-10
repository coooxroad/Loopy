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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import com.loopy.app.macro.Macro
import com.loopy.app.macro.Stroke
import com.loopy.app.macro.TouchSample
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.ui.theme.NeuBase
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

private val TraceStart = Color(0xFF3B82F6)
private val TraceEnd = Color(0xFFEFF5FF)
private const val WINDOW_MS = 150L
private val TapColor = Color(0xFF7FB3FF)
private val HoldColor = Color(0xFFC4A7FF)
private val SwipeColor = Color(0xFF8FE0B0)
private const val DP_PER_SEC = 68f // 타임라인 1초 = 68dp

@Composable
fun MacroEditorScreen(macro: Macro, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val hasVideo = macro.videoPath != null

    // 몰입 모드: 상태바/네비바 숨김, 나갈 때 복구
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val macroDurationMs = remember(macro) {
        (macro.strokes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L).coerceAtLeast(1L)
    }

    val player = remember(macro.videoPath) {
        if (!hasVideo) null else ExoPlayer.Builder(context).build().apply {
            val path = macro.videoPath!!
            val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
            setMediaItem(MediaItem.fromUri(uri)); prepare()
            setSeekParameters(SeekParameters.CLOSEST_SYNC) // 스크럽 시 즉시 반응
        }
    }

    var playing by remember { mutableStateOf(false) }
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
    val previewH = remember {
        val dm = context.resources.displayMetrics
        (dm.heightPixels / dm.density * 0.4f).dp
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

    val playheadStrokeMs = positionMs - macro.videoOffsetMs

    Box(Modifier.fillMaxSize().background(NeuBase)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(10.dp))
            // 상단 바
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
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

            // 프리뷰 + 트레이서
            Box(
                Modifier.fillMaxWidth().height(previewH).padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color(0xFF0A0B0F)),
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
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("영상 없음", color = Color(0xFF3A3F50), fontSize = 14.sp)
                }
                TraceOverlay(macro.strokes, playheadStrokeMs, contentAspect, macro.rotation)
            }

            Spacer(Modifier.height(8.dp))

            // 재생 컨트롤
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmt(positionMs), color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(50.dp).clip(CircleShape).background(Accent).clickable { togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Text(if (playing) "❚❚" else "▶", color = Color.White, fontSize = 18.sp) }
                Text(
                    fmt(totalMs), color = TextLo, fontSize = 12.sp,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // 타임라인 (재생헤드 중앙 고정, 필름스트립 흐름)
            Timeline(
                macro = macro, totalMs = totalMs, positionMs = positionMs,
                dpPerSec = DP_PER_SEC, density = density,
                onScrubTime = { t ->
                    playing = false; player?.playWhenReady = false
                    positionMs = t; player?.seekTo(t)
                },
                onScrubbingChange = { userScrubbing = it },
                userScrubbing = userScrubbing,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // 하단 툴바 (구조만)
            Box(Modifier.fillMaxWidth().background(LoopyCard)
                .border(1.dp, CardStroke, RoundedCornerShape(0.dp))) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolItem("분할", Icons.Filled.ContentCut)
                    ToolItem("대기 삽입", Icons.Filled.MoreTime)
                    ToolItem("삭제", Icons.Filled.Delete)
                }
            }
        }
    }
}

@Composable
private fun Timeline(
    macro: Macro,
    totalMs: Long,
    positionMs: Long,
    dpPerSec: Float,
    density: androidx.compose.ui.unit.Density,
    onScrubTime: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    userScrubbing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val pxPerMs = with(density) { dpPerSec.dp.toPx() } / 1000f
    val thumbs = remember { mutableStateListOf<ImageBitmap?>() }
    val trackH = 46.dp
    val rulerH = 18.dp
    val thumbWpx = with(density) { dpPerSec.dp.toPx() }.toInt().coerceAtLeast(1)
    val thumbHpx = with(density) { trackH.toPx() }.toInt().coerceAtLeast(1)

    val secCount = ceil(totalMs / 1000f).toInt().coerceAtLeast(1)

    // 프레임 썸네일 추출 (백그라운드, 진행형)
    LaunchedEffect(macro.videoPath, thumbWpx) {
        thumbs.clear()
        val path = macro.videoPath ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            runCatching {
                val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
                r.setDataSource(context, uri)
                for (i in 0 until secCount) {
                    val tUs = i * 1000_000L
                    val bmp = runCatching {
                        r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { Bitmap.createScaledBitmap(it, thumbWpx, thumbHpx, true) }
                    }.getOrNull()
                    val ib = bmp?.asImageBitmap()
                    withContext(Dispatchers.Main) { thumbs.add(ib) }
                }
            }
            runCatching { r.release() }
        }
    }

    // 사용자 스크롤 → 시간 반영 + 스크러빙 상태 갱신
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.isScrollInProgress }
            .collect { (px, inProgress) ->
                onScrubbingChange(inProgress)
                if (inProgress) {
                    onScrubTime((px / pxPerMs).toLong().coerceIn(0L, totalMs))
                }
            }
    }
    // 재생/위치 → 스크롤 따라가기 (사용자 스크롤 중이 아닐 때)
    LaunchedEffect(positionMs, userScrubbing) {
        if (!userScrubbing) {
            val target = (positionMs * pxPerMs).toInt().coerceIn(0, scrollState.maxValue)
            if (abs(scrollState.value - target) > 1) runCatching { scrollState.scrollTo(target) }
        }
    }

    // 스트로크를 레인(멀티터치 겹침)으로 배치
    val lanes = remember(macro) { assignLanes(macro.strokes) }
    val laneCount = (lanes.maxOfOrNull { it.second }?.plus(1)) ?: 0
    val strokeTrackH = (laneCount.coerceAtLeast(1) * 16).dp

    BoxWithConstraints(modifier.background(NeuBase)) {
        val viewportPx = constraints.maxWidth
        val halfPx = viewportPx / 2f
        val contentPx = viewportPx + totalMs * pxPerMs
        val contentDp = with(density) { contentPx.toDp() }
        val halfDp = with(density) { halfPx.toDp() }

        Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .horizontalScroll(scrollState),
            ) {
                Column(Modifier.width(contentDp)) {
                    // 눈금자
                    Canvas(Modifier.fillMaxWidth().height(rulerH)) {
                        val yb = size.height
                        for (sec in 0..secCount) {
                            val x = halfPx + sec * 1000f * pxPerMs
                            drawLine(CardStroke, Offset(x, yb * 0.4f), Offset(x, yb),
                                strokeWidth = 2f)
                        }
                    }
                    // 필름스트립
                    Row(Modifier.height(trackH)) {
                        Spacer(Modifier.width(halfDp))
                        for (i in 0 until secCount) {
                            val ib = thumbs.getOrNull(i)
                            Box(
                                Modifier.width(dpPerSec.dp).height(trackH)
                                    .background(Color(0xFF1A1D26)),
                            ) {
                                if (ib != null) {
                                    Image(ib, contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop)
                                }
                            }
                        }
                        Spacer(Modifier.width(halfDp))
                    }
                    Spacer(Modifier.height(6.dp))
                    // 스트로크 트랙
                    Canvas(Modifier.fillMaxWidth().height(strokeTrackH)) {
                        for ((idx, stroke) in macro.strokes.withIndex()) {
                            val lane = lanes.firstOrNull { it.first == idx }?.second ?: 0
                            val x = halfPx + stroke.startMs * pxPerMs
                            val w = (stroke.durationMs * pxPerMs).coerceAtLeast(6f)
                            val y = (lane * 16).dp.toPx()
                            drawRoundRect(
                                color = strokeColor(stroke),
                                topLeft = Offset(x, y + 2f),
                                size = androidx.compose.ui.geometry.Size(w, 12f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                            )
                        }
                    }
                }
            }
        }

        // 중앙 재생헤드 (고정)
        Box(
            Modifier.fillMaxSize().padding(top = 6.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.width(2.dp).fillMaxSize().background(Accent))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = TextLo, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextLo, fontSize = 11.sp)
    }
}

@Composable
private fun TraceOverlay(strokes: List<Stroke>, playheadStrokeMs: Long, contentAspect: Float, rotationDeg: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val bw = size.width; val bh = size.height
        val boxAspect = bw / bh
        val dispW: Float; val dispH: Float
        if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
        else { dispH = bh; dispW = bh * contentAspect }
        val offX = (bw - dispW) / 2f; val offY = (bh - dispH) / 2f
        fun rot(nx: Float, ny: Float): Pair<Float, Float> = when (rotationDeg) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }
        fun mapPt(nx: Float, ny: Float): Offset {
            val (rx, ry) = rot(nx, ny)
            return Offset(offX + rx * dispW, offY + ry * dispH)
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
            val revealT = relT.coerceIn(0L, s.durationMs)
            val dur = s.durationMs.coerceAtLeast(1L)
            val pressing = relT in 0..s.durationMs
            var prev: Offset? = null; var prevFrac = 0f
            for (smp in samples) {
                if (smp.t > revealT + 40L) break
                val p = mapPt(smp.nx, smp.ny)
                val frac = (smp.t.toFloat() / dur).coerceIn(0f, 1f)
                if (prev != null) {
                    val c = lerp(TraceStart, TraceEnd, (prevFrac + frac) / 2f)
                        .copy(alpha = edge * (if (pressing) 1f else 0.5f))
                    drawLine(c, prev, p, strokeWidth = if (pressing) 9f else 6f)
                }
                prev = p; prevFrac = frac
            }
            val cur = sampleAt(samples, revealT)
            if (cur != null) {
                val p = mapPt(cur.first, cur.second)
                if (pressing) {
                    drawIntoCanvas { canvas ->
                        val glow = (255 * edge).toInt().coerceIn(0, 255)
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            color = android.graphics.Color.WHITE
                            setShadowLayer(50f, 0f, 0f, android.graphics.Color.argb(glow, 59, 130, 246))
                        }
                        canvas.nativeCanvas.drawCircle(p.x, p.y, 13f, paint)
                    }
                    drawCircle(TraceStart.copy(alpha = 0.85f * edge), radius = 15f, center = p,
                        style = DrawStroke(width = 3f))
                } else {
                    drawCircle(TraceEnd.copy(alpha = 0.4f * edge), radius = 6f, center = p)
                }
            }
        }
    }
}

/** 스트로크를 겹치지 않는 레인에 배치. 반환: (strokeIndex, lane). */
private fun assignLanes(strokes: List<Stroke>): List<Pair<Int, Int>> {
    val order = strokes.indices.sortedBy { strokes[it].startMs }
    val laneEnd = ArrayList<Long>()
    val result = ArrayList<Pair<Int, Int>>()
    for (idx in order) {
        val s = strokes[idx]
        var lane = laneEnd.indexOfFirst { it <= s.startMs }
        if (lane < 0) { lane = laneEnd.size; laneEnd.add(0L) }
        laneEnd[lane] = s.startMs + s.durationMs
        result.add(idx to lane)
    }
    return result
}

private fun strokeColor(s: Stroke): Color {
    var moved = 0.0
    val f = s.samples.firstOrNull()
    if (f != null) for (p in s.samples) {
        moved = maxOf(moved, hypot((p.nx - f.nx).toDouble(), (p.ny - f.ny).toDouble()))
    }
    return when {
        moved > 0.03 -> SwipeColor
        s.durationMs > 300 -> HoldColor
        else -> TapColor
    }
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
    val s = ms / 1000; return "%d:%02d.%01d".format(s / 60, s % 60, (ms % 1000) / 100)
}
