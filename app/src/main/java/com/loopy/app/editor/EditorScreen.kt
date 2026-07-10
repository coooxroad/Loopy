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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
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
import com.loopy.app.ui.theme.AnimatedBottomGradient
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

private val TraceStart = Color(0xFF3B82F6)
private val TraceEnd = Color(0xFFEFF5FF)
private const val WINDOW_MS = 150L
private const val DP_PER_SEC = 68f

@Composable
fun MacroEditorScreen(macro: Macro, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val hasVideo = macro.videoPath != null

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
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
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
        (dm.heightPixels / dm.density * 0.42f).dp
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

            // 영상: 가로 꽉 찬 검은 직사각형
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
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("영상 없음", color = Color(0xFF3A3F50), fontSize = 14.sp)
                }
                TraceOverlay(macro.strokes, playheadStrokeMs, contentAspect, macro.rotation)
            }

            // 인포바: 볼록 뉴모피즘 각진 직사각형(좌우 꽉, 슬림, 한 칸 위)
            Box(
                Modifier.fillMaxWidth().neuRaised().background(NeuBase)
                    .padding(horizontal = 18.dp, vertical = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "${fmt(positionMs)} / ${fmt(totalMs)}",
                    color = TextLo, fontSize = 12.sp,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PlayPauseFilled(playing) { togglePlay() }
                }
            }

            // 편집공간: 오목(함몰) — 위쪽 이너 섀도우로 파인 느낌
            Column(Modifier.fillMaxWidth().background(Color(0xFFE6EAF2))) {
                Box(
                    Modifier.fillMaxWidth().height(9.dp).background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFC9D0E0).copy(alpha = 0.55f), Color.Transparent),
                        ),
                    ),
                )
                Timeline(
                    macro = macro, totalMs = totalMs, positionMs = positionMs,
                    dpPerSec = DP_PER_SEC, density = density,
                    onScrubTime = { t ->
                        playing = false; player?.playWhenReady = false
                        positionMs = t; player?.seekTo(t)
                    },
                    onScrubbingChange = { userScrubbing = it },
                    userScrubbing = userScrubbing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.weight(1f)) // 하단 여백 — 애니메이션 그라데이션이 보임
        }
    }
}

/** 볼록 뉴모피즘(각진 직사각형): 위-왼쪽 하이라이트 + 아래-오른쪽 그림자. */
private fun Modifier.neuRaised() = this.drawBehind {
    val off = 5.dp.toPx(); val blur = 11.dp.toPx()
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
        val dark = android.graphics.Paint().apply {
            isAntiAlias = true; color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, off, off, 0xFFC9D0E0.toInt())
        }
        fw.drawRect(rect, dark)
        val light = android.graphics.Paint().apply {
            isAntiAlias = true; color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, -off, -off, 0xFFFFFFFF.toInt())
        }
        fw.drawRect(rect, light)
    }
}

/** 채운 차콜 재생/퍼즈 벡터 (모서리 약간 둥글게). */
@Composable
private fun PlayPauseFilled(playing: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(19.dp)) {
            val c = Color(0xFF2B2D42)
            val w = size.width; val h = size.height
            if (playing) {
                val bw = w * 0.28f
                val gap = w * 0.16f
                val bh = h * 0.84f
                val top = (h - bh) / 2f
                val x1 = w / 2f - gap / 2f - bw
                val x2 = w / 2f + gap / 2f
                val cr = androidx.compose.ui.geometry.CornerRadius(bw * 0.45f, bw * 0.45f)
                drawRoundRect(c, topLeft = Offset(x1, top),
                    size = androidx.compose.ui.geometry.Size(bw, bh), cornerRadius = cr)
                drawRoundRect(c, topLeft = Offset(x2, top),
                    size = androidx.compose.ui.geometry.Size(bw, bh), cornerRadius = cr)
            } else {
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.16f)
                    lineTo(w * 0.84f, h * 0.5f)
                    lineTo(w * 0.24f, h * 0.84f)
                    close()
                }
                drawPath(p, c) // 채움
                drawPath(p, c, style = DrawStroke(width = w * 0.16f, join = StrokeJoin.Round)) // 모서리 둥글게
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
    val trackH = 52.dp
    val rulerH = 18.dp
    val cardVPad = 6.dp
    val thumbHpx = with(density) { trackH.toPx() }.toInt().coerceAtLeast(1)
    val secCount = ceil(totalMs / 1000f).toInt().coerceAtLeast(1)

    // 프레임 썸네일 추출(크롭용: 비율 유지 스케일 → Image에서 Crop)
    LaunchedEffect(macro.videoPath) {
        thumbs.clear()
        val path = macro.videoPath ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            runCatching {
                val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
                r.setDataSource(context, uri)
                for (i in 0 until secCount) {
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

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.isScrollInProgress }
            .collect { (px, inProgress) ->
                onScrubbingChange(inProgress)
                if (inProgress) onScrubTime((px / pxPerMs).toLong().coerceIn(0L, totalMs))
            }
    }
    LaunchedEffect(positionMs, userScrubbing) {
        if (!userScrubbing) {
            val target = (positionMs * pxPerMs).toInt().coerceIn(0, scrollState.maxValue)
            if (abs(scrollState.value - target) > 1) runCatching { scrollState.scrollTo(target) }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(112.dp)) {
        val viewportPx = constraints.maxWidth
        val halfPx = viewportPx / 2f
        val contentPx = viewportPx + totalMs * pxPerMs
        val contentDp = with(density) { contentPx.toDp() }
        val halfDp = with(density) { halfPx.toDp() }
        val cardH = trackH + cardVPad * 2

        Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
            Column(Modifier.fillMaxWidth().horizontalScroll(scrollState).width(contentDp)) {
                // 눈금자: 초 숫자(뮤트 그레이) + 눈금선
                Canvas(Modifier.fillMaxWidth().height(rulerH)) {
                    val yb = size.height
                    val txt = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(138, 141, 160) // TextLo
                        textSize = 9.sp.toPx()
                        isAntiAlias = true
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
                // 필름스트립: 양옆 런웨이(투명, 오목 배경 보임) + 흰 둥근 카드가 흐름
                Row(Modifier.height(cardH), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(halfDp))
                    Box(
                        Modifier.height(cardH).clip(RoundedCornerShape(12.dp)).background(LoopyCard),
                    ) {
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
            }
            // 중앙 재생헤드: 슬림 차콜 + 은은한 그림자
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val phTop = 0f
                val phBot = with(density) { (rulerH + 4.dp + cardH + 6.dp).toPx() }
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.rgb(43, 45, 66) // TextHi
                        strokeWidth = with(density) { 2.5.dp.toPx() }
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        setShadowLayer(with(density) { 5.dp.toPx() }, 0f,
                            with(density) { 1.dp.toPx() }, android.graphics.Color.argb(60, 0, 0, 0))
                    }
                    canvas.nativeCanvas.drawLine(x, phTop, x, phBot, paint)
                }
            }
        }
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
