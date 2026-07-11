#!/data/data/com.termux/files/usr/bin/bash
# 편집기 undo/redo 2/2
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat >> "app/src/main/java/com/loopy/app/editor/EditorScreen.kt" << 'LOOPY_EOF'
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

/** 볼록 뉴모피즘(좌우 꽉 찬 바용): 수직 하이라이트/그림자. */
private fun Modifier.neuRaised() = this.drawBehind {
    val off = 3.dp.toPx(); val blur = 7.dp.toPx()
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
        val dark = android.graphics.Paint().apply {
            isAntiAlias = true; color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, 0f, off, 0xFFD3D9E4.toInt())
        }
        fw.drawRect(rect, dark)
        val light = android.graphics.Paint().apply {
            isAntiAlias = true; color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, 0f, -off, 0xFFFFFFFF.toInt())
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
                            onClick = { onSelect(i) },
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

/** 스트로크 블록: 기본 푸른색 볼록, 선택 시 흰색 + 푸른 테두리가 약간 커짐. */
@Composable
private fun StrokeBlock(selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Box(modifier.clickable { onClick() }) {
        val inset = if (selected) 0.dp else 2.dp
        Box(
            Modifier.fillMaxSize().padding(inset)
                .shadow(if (selected) 5.dp else 2.dp, shape, clip = false)
                .clip(shape)
                .background(if (selected) Color.White else TraceStart)
                .then(if (selected) Modifier.border(2.5.dp, TraceStart, shape) else Modifier),
        )
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
    val s = ms / 1000; return "%d:%02d.%01d".format(s / 60, s % 60, (ms % 1000) / 100)
}
LOOPY_EOF
echo "2/2 완료."
git add -A
git commit -m "fix: selectedStroke 선언을 undo/redo 위로 이동(스코프)"
git push
echo "푸시 완료!"

