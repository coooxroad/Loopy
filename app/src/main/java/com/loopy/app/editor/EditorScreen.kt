package com.loopy.app.editor

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.loopy.app.macro.Macro
import com.loopy.app.macro.Stroke
import kotlinx.coroutines.delay
import java.io.File

private val StrokeStart = Color(0xFF3B82F6) // 파랑 (스트로크 시작)
private val StrokeEnd = Color(0xFFFFFFFF)   // 흰색 (스트로크 끝)
private const val WINDOW_MS = 150L          // 재생헤드 앞뒤 표시 창

/** 편집기 1단계: 영상 프리뷰 + 그라데이션 스트로크 오버레이 + 싱크 재생. */
@Composable
fun MacroEditorScreen(macro: Macro, onBack: () -> Unit) {
    val context = LocalContext.current
    val hasVideo = macro.videoPath != null

    // 매크로 전체 길이(스트로크 기준)
    val macroDurationMs = remember(macro) {
        (macro.strokes.maxOfOrNull { it.startMs + it.durationMs } ?: 0L).coerceAtLeast(1L)
    }

    // ExoPlayer (영상 있을 때만)
    val player = remember(macro.videoPath) {
        if (!hasVideo) null else ExoPlayer.Builder(context).build().apply {
            val path = macro.videoPath!!
            val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) } // 영상 있으면 영상시각, 없으면 가상시계

    // 총 길이: 영상 있으면 영상 길이(로드되면), 없으면 매크로 길이 (remember 안 함 → 갱신됨)
    val totalMs: Long = run {
        val d = player?.duration ?: 0L
        if (hasVideo && d > 0) d else macroDurationMs + macro.videoOffsetMs
    }

    // 프리뷰 박스 = 실제 화면 비율(녹화 영상이 화면 미러라, 비율 맞추면 스트로크 좌표가 정렬됨)
    val screenAspect = remember {
        val dm = context.resources.displayMetrics
        (dm.widthPixels.toFloat() / dm.heightPixels.toFloat()).coerceIn(0.3f, 1f)
    }

    // 재생 루프
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (player != null) {
            player.playWhenReady = true
            while (playing) {
                positionMs = player.currentPosition
                if (!player.isPlaying && player.playbackState == Player.STATE_ENDED) {
                    playing = false; break
                }
                delay(16)
            }
            player.playWhenReady = false
        } else {
            // 가상 시계 (영상 없는 매크로)
            var last = System.currentTimeMillis()
            while (playing) {
                val now = System.currentTimeMillis()
                positionMs += (now - last); last = now
                if (positionMs >= totalMs) { positionMs = totalMs; playing = false; break }
                delay(16)
            }
        }
    }

    // 스트로크 타임라인 상의 현재 재생헤드(ms). 영상시각 - offset.
    val playheadStrokeMs = positionMs - macro.videoOffsetMs

    Column(Modifier.fillMaxSize().background(Color(0xFF0E1016))) {
        // ── 상단 바 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ 뒤로", color = Color(0xFF9AA0B4), fontSize = 15.sp,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(14.dp))
            Text(macro.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        // ── 영상 프리뷰 + 스트로크 오버레이 ──
        Box(
            Modifier.fillMaxWidth().aspectRatio(screenAspect)
                .background(Color(0xFF000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (player != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            this.player = player
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("영상 없음", color = Color(0xFF4A4F60), fontSize = 14.sp)
            }
            StrokeOverlay(macro.strokes, playheadStrokeMs)
        }

        // ── 재생 컨트롤 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (playing) "❚❚" else "▶",
                color = Color.White, fontSize = 20.sp,
                modifier = Modifier.clickable {
                    if (positionMs >= totalMs) { positionMs = 0L; player?.seekTo(0) }
                    playing = !playing
                },
            )
            Spacer(Modifier.width(14.dp))
            Slider(
                value = positionMs.coerceIn(0, totalMs).toFloat(),
                onValueChange = {
                    playing = false
                    positionMs = it.toLong()
                    player?.seekTo(positionMs)
                },
                valueRange = 0f..totalMs.toFloat(),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "${fmt(positionMs)} / ${fmt(totalMs)}",
            color = Color(0xFF9AA0B4), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        Spacer(Modifier.height(16.dp))
        // ── 아래: 편집 타임라인 (2단계에서 구현) ──
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(16.dp)
                .background(Color(0xFF14161E)),
            contentAlignment = Alignment.Center,
        ) {
            Text("타임라인 편집 · 다음 단계", color = Color(0xFF3A3F50), fontSize = 13.sp)
        }
    }
}

/** 현재 재생헤드 근처(±WINDOW_MS)의 스트로크를 그라데이션(파랑→흰) 선으로. */
@Composable
private fun StrokeOverlay(strokes: List<Stroke>, playheadStrokeMs: Long) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (s in strokes) {
            val relT = playheadStrokeMs - s.startMs
            // 활성: 시작 150ms 전 ~ 끝 150ms 후
            if (relT < -WINDOW_MS || relT > s.durationMs + WINDOW_MS) continue
            // 페이드(가장자리에서 흐려짐)
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

            // 지나온 구간을 그라데이션 선으로
            var prev: Offset? = null
            var prevFrac = 0f
            for (smp in samples) {
                if (smp.t > revealT + 40L) break // 아직 안 지난 샘플
                val p = Offset(smp.nx * w, smp.ny * h)
                val frac = (smp.t.toFloat() / dur).coerceIn(0f, 1f)
                if (prev != null) {
                    val c = lerp(StrokeStart, StrokeEnd, (prevFrac + frac) / 2f).copy(alpha = edge)
                    drawLine(c, prev, p, strokeWidth = 8f)
                }
                prev = p; prevFrac = frac
            }
            // 현재 위치 점(글로우)
            val cur = sampleAt(samples, revealT)
            if (cur != null) {
                val p = Offset(cur.first * w, cur.second * h)
                drawCircle(StrokeEnd.copy(alpha = 0.25f * edge), radius = 22f, center = p)
                drawCircle(StrokeEnd.copy(alpha = edge), radius = 9f, center = p)
            }
        }
    }
}

/** revealT 시점의 보간 좌표. */
private fun sampleAt(samples: List<com.loopy.app.macro.TouchSample>, t: Long): Pair<Float, Float>? {
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
