#!/data/data/com.termux/files/usr/bin/bash
# 편집기 개선: 영상 비율 유지(FIT)+실제 rect 정렬+진짜 눌림 글로우
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat > "app/src/main/java/com/loopy/app/editor/EditorScreen.kt" << 'LOOPY_EOF'
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
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) } // 영상 있으면 영상시각, 없으면 가상시계

    // 콘텐츠 비율(가로/세로). 화면 미러 기준 기본값, 영상 로드되면 실제 해상도로 갱신.
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

    // 콘텐츠 비율: 영상 있으면 영상 실제 비율, 없으면 화면 비율
    val contentAspect = if (hasVideo) videoAspect else screenAspect

    // 총 길이: 영상 있으면 영상 길이(로드되면), 없으면 매크로 길이
    val totalMs: Long = run {
        val d = player?.duration ?: 0L
        if (hasVideo && d > 0) d else macroDurationMs + macro.videoOffsetMs
    }

    // 위쪽 영상 영역 높이 = 화면의 약 52% 고정
    val previewH = remember {
        val dm = context.resources.displayMetrics
        (dm.heightPixels / dm.density * 0.52f).dp
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

        // ── 영상 프리뷰 + 스트로크 오버레이 (고정 높이, 비율 유지 FIT) ──
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
                Text("영상 없음", color = Color(0xFF4A4F60), fontSize = 14.sp)
            }
            // 스트로크는 실제 영상이 그려진 rect 기준으로 매핑 (FIT 레터박스 보정)
            StrokeOverlay(macro.strokes, playheadStrokeMs, contentAspect)
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

/** 재생헤드 근처(±WINDOW_MS)의 스트로크를 그라데이션 선으로. 실제 눌리는 순간만 글로우. */
@Composable
private fun StrokeOverlay(strokes: List<Stroke>, playheadStrokeMs: Long, contentAspect: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val bw = size.width
        val bh = size.height
        // 콘텐츠(영상) 비율에 맞춰 박스 안에 FIT된 실제 rect 계산 (레터박스 보정)
        val boxAspect = bw / bh
        val dispW: Float; val dispH: Float
        if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
        else { dispH = bh; dispW = bh * contentAspect }
        val offX = (bw - dispW) / 2f
        val offY = (bh - dispH) / 2f
        fun mapX(nx: Float) = offX + nx * dispW
        fun mapY(ny: Float) = offY + ny * dispH

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
            // "지금 진짜 눌리는 중"? = 재생헤드가 down~up 구간 안
            val pressing = relT in 0..s.durationMs

            // 지나온 구간 = 그라데이션 선
            var prev: Offset? = null
            var prevFrac = 0f
            for (smp in samples) {
                if (smp.t > revealT + 40L) break
                val p = Offset(mapX(smp.nx), mapY(smp.ny))
                val frac = (smp.t.toFloat() / dur).coerceIn(0f, 1f)
                if (prev != null) {
                    val c = lerp(StrokeStart, StrokeEnd, (prevFrac + frac) / 2f)
                        .copy(alpha = edge * (if (pressing) 1f else 0.55f))
                    drawLine(c, prev, p, strokeWidth = if (pressing) 9f else 6f)
                }
                prev = p; prevFrac = frac
            }
            // 현재 위치 — 진짜 눌리는 중이면 강한 글로우, 여운이면 흐리게
            val cur = sampleAt(samples, revealT)
            if (cur != null) {
                val p = Offset(mapX(cur.first), mapY(cur.second))
                if (pressing) {
                    drawCircle(StrokeStart.copy(alpha = 0.20f * edge), radius = 40f, center = p)
                    drawCircle(StrokeEnd.copy(alpha = 0.35f * edge), radius = 22f, center = p)
                    drawCircle(StrokeEnd.copy(alpha = edge), radius = 11f, center = p)
                } else {
                    drawCircle(StrokeEnd.copy(alpha = 0.4f * edge), radius = 7f, center = p)
                }
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
LOOPY_EOF
echo "완료."
git add -A
git commit -m "편집기: 영상 비율유지(FIT)+스트로크 rect정렬+진짜 눌림 글로우"
git push
echo "푸시 완료!"

