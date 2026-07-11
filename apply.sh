#!/data/data/com.termux/files/usr/bin/bash
# 3/3
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat >> "app/src/main/java/com/loopy/app/editor/EditorScreen.kt" << 'LOOPY_EOF'
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

/** 스트로크 블록: 캡처=파랑, 추가=초록. 색맞춤 뉴모피즘(우하 그림자/좌상 글로우). */
@Composable
private fun StrokeBlock(selected: Boolean, added: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(7.dp)
    val base = if (added) AddedGreen else TraceStart
    val fill = if (selected) Color.White else base
    Box(modifier.clickable { onClick() }) {
        Box(
            Modifier.fillMaxSize().padding(1.dp)
                .neu(fill, cornerDp = 7f, offDp = if (selected) 3f else 2.5f,
                    blurDp = if (selected) 7f else 5f, raised = true)
                .clip(shape)
                .background(fill)
                .then(if (selected) Modifier.border(2.dp, base, shape) else Modifier),
        )
    }
}

/**
 * 선택된 스트로크를 덮는 직사각형. 영상 위에서 드래그하면 스트로크 전체가 평행이동.
 * 파란 얇은 테두리 + 뉴모피즘 입체, 안은 불투명 흰색. 트레이서보다 아래 레이어.
 */
@Composable
private fun StrokeMoveBox(
    stroke: Stroke, contentAspect: Float, defaultRot: Int,
    onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit,
) {
    val rotDeg = if (stroke.rotation >= 0) stroke.rotation else defaultRot
    Box(
        Modifier.fillMaxSize().pointerInput(stroke, contentAspect) {
            detectDragGestures(
                onDrag = { change, amount ->
                    change.consume()
                    val bw = size.width.toFloat(); val bh = size.height.toFloat()
                    val boxAspect = bw / bh
                    val dispW: Float; val dispH: Float
                    if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
                    else { dispH = bh; dispW = bh * contentAspect }
                    // 화면 dx,dy → 회전 역변환 → 정규화 델타
                    val sx = amount.x / dispW; val sy = amount.y / dispH
                    val (dnx, dny) = when (rotDeg) {
                        90 -> (-sy) to sx
                        180 -> (-sx) to (-sy)
                        270 -> sy to (-sx)
                        else -> sx to sy
                    }
                    onDrag(dnx, dny)
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
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (smp in stroke.samples) {
                val (rx, ry) = rotNorm(smp.nx, smp.ny, rotDeg)
                val x = offX + rx * dispW; val y = offY + ry * dispH
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            val pad = 22f
            val l = minX - pad; val tp = minY - pad
            val w = (maxX - minX) + pad * 2; val h = (maxY - minY) + pad * 2
            val cr = 14f
            // 뉴모피즘: 파란 그림자 우하 + 밝은 글로우 좌상 (얇게)
            drawIntoCanvas { canvas ->
                val dark = android.graphics.Paint().apply {
                    isAntiAlias = true; color = android.graphics.Color.WHITE
                    setShadowLayer(9f, 4f, 4f, android.graphics.Color.argb(120, 37, 84, 160))
                }
                canvas.nativeCanvas.drawRoundRect(l, tp, l + w, tp + h, cr, cr, dark)
                val light = android.graphics.Paint().apply {
                    isAntiAlias = true; color = android.graphics.Color.WHITE
                    setShadowLayer(9f, -4f, -4f, android.graphics.Color.argb(150, 190, 220, 255))
                }
                canvas.nativeCanvas.drawRoundRect(l, tp, l + w, tp + h, cr, cr, light)
            }
            // 흰 불투명 채움 + 얇은 파란 테두리
            drawRoundRect(Color.White, topLeft = Offset(l, tp), size = Size(w, h),
                cornerRadius = CornerRadius(cr, cr))
            drawRoundRect(TraceStart.copy(alpha = 0.95f), topLeft = Offset(l, tp), size = Size(w, h),
                cornerRadius = CornerRadius(cr, cr), style = DrawStroke(width = 2f))
        }
    }
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
private fun TraceOverlay(strokes: List<Stroke>, playheadStrokeMs: Long, contentAspect: Float, rotationDeg: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val bw = size.width; val bh = size.height
        val boxAspect = bw / bh
        val dispW: Float; val dispH: Float
        if (contentAspect > boxAspect) { dispW = bw; dispH = bw / contentAspect }
        else { dispH = bh; dispW = bh * contentAspect }
        val offX = (bw - dispW) / 2f; val offY = (bh - dispH) / 2f
        fun mapPt(nx: Float, ny: Float, rotDeg: Int): Offset {
            val (rx, ry) = rotNorm(nx, ny, rotDeg)
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
            val sRot = if (s.rotation >= 0) s.rotation else rotationDeg
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
LOOPY_EOF
echo "3/3 완료."
git add -A
git commit -m "뉴모피즘 재정의(색맞춤 우하그림자+좌상글로우), 블록/시계 적용, 인포바 글로우 완화, 시간 소수2자리+키패드, 스트로크별 회전보정, 영상 드래그 박스로 터치위치 이동"
git push
echo "푸시 완료!"

