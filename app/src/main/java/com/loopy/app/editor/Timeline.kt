package com.loopy.app.editor

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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.loopy.app.core.stroke.StrokeOps
import com.loopy.app.core.record.PlacedStroke
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.ui.theme.NeuBase
import com.loopy.app.ui.theme.Depth
import com.loopy.app.ui.theme.neu
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun Timeline(
    strokes: List<PlacedStroke>,
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

    val lanes = StrokeOps.assignLanes(strokes)
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
                        val wDp = with(density) { (s.stroke.durationMs * pxPerMs).toDp() }.coerceAtLeast(12.dp)
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
internal fun StrokeBlock(
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
                .neu(fill = if (selected) Color.White else base, corner = 7.dp, depth = Depth.SM)
                .then(if (selected) Modifier.border(2.dp, base, shape) else Modifier),
        )
    }
}

