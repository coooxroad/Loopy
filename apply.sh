#!/data/data/com.termux/files/usr/bin/bash
# 회전 정렬 2/2: 편집기 3존 UI + 회전 트레이서 + 글로우 강화
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat > "app/src/main/java/com/loopy/app/editor/EditorScreen.kt" << 'LOOPY_EOF'
package com.loopy.app.editor

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.loopy.app.macro.Macro
import com.loopy.app.macro.Stroke
import com.loopy.app.macro.TouchSample
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.AnimatedBottomGradient
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.ui.theme.NeuBase
import com.loopy.app.ui.theme.SoftCard
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo
import kotlinx.coroutines.delay
import java.io.File

private val TraceStart = Color(0xFF3B82F6)
private val TraceEnd = Color(0xFFEFF5FF)
private const val WINDOW_MS = 150L

/** 편집기: 3존(프리뷰/재생·타임라인/툴바) + 그라데이션 트레이서 + 회전 정렬. */
@Composable
fun MacroEditorScreen(macro: Macro, onBack: () -> Unit) {
    val context = LocalContext.current
    val hasVideo = macro.videoPath != null

    val macroDurationMs = remember(macro) {
        (macro.strokes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L).coerceAtLeast(1L)
    }

    val player = remember(macro.videoPath) {
        if (!hasVideo) null else ExoPlayer.Builder(context).build().apply {
            val path = macro.videoPath!!
            val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
            setMediaItem(MediaItem.fromUri(uri)); prepare()
        }
    }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }

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
        (dm.heightPixels / dm.density * 0.46f).dp
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (player != null) {
            while (playing) {
                positionMs = player.currentPosition
                if (player.playbackState == Player.STATE_ENDED) {
                    player.playWhenReady = false; playing = false; break
                }
                delay(16)
            }
        } else {
            var last = System.currentTimeMillis()
            while (playing) {
                val now = System.currentTimeMillis()
                positionMs += (now - last); last = now
                if (positionMs >= totalMs) { positionMs = totalMs; playing = false; break }
                delay(16)
            }
        }
    }

    fun togglePlay() {
        if (positionMs >= totalMs) { positionMs = 0L; player?.seekTo(0) }
        playing = !playing
        player?.playWhenReady = playing
    }
    fun seekTo(ms: Long) {
        playing = false
        player?.playWhenReady = false
        positionMs = ms.coerceIn(0L, totalMs)
        player?.seekTo(positionMs)
    }

    val playheadStrokeMs = positionMs - macro.videoOffsetMs

    Box(Modifier.fillMaxSize().background(NeuBase)) {
        AnimatedBottomGradient()
        Column(Modifier.fillMaxSize()) {
            // ── 상단 바 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
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

            // ── 프리뷰(검정) + 트레이서 ──
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

            Spacer(Modifier.height(10.dp))

            // ── 재생 컨트롤 (플레이 중앙) ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmt(positionMs), color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Accent).clickable { togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Text(if (playing) "❚❚" else "▶", color = Color.White, fontSize = 19.sp) }
                Text(
                    fmt(totalMs), color = TextLo, fontSize = 12.sp,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 타임라인 스크러버 (2단계에서 트랙으로 확장) ──
            SoftCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp), padding = 12.dp) {
                Slider(
                    value = positionMs.coerceIn(0, totalMs).toFloat(),
                    onValueChange = { seekTo(it.toLong()) },
                    valueRange = 0f..totalMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = CardStroke,
                    ),
                )
                Text("타임라인 · 트랙/분할은 다음 단계", color = TextLo, fontSize = 11.sp)
            }

            Spacer(Modifier.weight(1f))

            // ── 하단 툴바 (상황별 도구 — 기능은 다음 단계) ──
            Box(
                Modifier.fillMaxWidth().background(LoopyCard)
                    .border(width = 1.dp, color = CardStroke, shape = RoundedCornerShape(0.dp)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
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
private fun androidx.compose.foundation.layout.RowScope.ToolItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(
        Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = TextLo, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextLo, fontSize = 11.sp)
    }
}

/** 재생헤드 근처의 스트로크를 그라데이션 선으로. 회전 정렬 + 흰 원+파란 글로우/링. */
@Composable
private fun TraceOverlay(strokes: List<Stroke>, playheadStrokeMs: Long, contentAspect: Float, rotationDeg: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val bw = size.width; val bh = size.height
        val boxAspect = bw / bh
        val dispW: Float; val dispH: Float
        if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
        else { dispH = bh; dispW = bh * contentAspect }
        val offX = (bw - dispW) / 2f; val offY = (bh - dispH) / 2f

        // 패널 고유좌표(nx,ny) → 화면(영상) 회전 방향으로 변환
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
                    // 파란 글로우(네이티브 blur) + 흰 원 + 파란 링
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
            val f = if (b.t == a.t) 0f else (t - a.t).toFloat() / (b.t - a.t)
            return (a.nx + (b.nx - a.nx) * f) to (a.ny + (b.ny - a.ny) * f)
        }
    }
    return samples.last().nx to samples.last().ny
}

private fun fmt(ms: Long): String {
    val s = ms / 1000; return "%d:%02d.%01d".format(s / 60, s % 60, (ms % 1000) / 100)
}
LOOPY_EOF
echo "완료."
git add -A
git commit -m "편집기 3존 UI(뉴모피즘)+회전 트레이서 정렬+글로우/파란링 강화, Macro rotation 저장"
git push
echo "푸시 완료!"

