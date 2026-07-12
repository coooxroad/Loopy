#!/data/data/com.termux/files/usr/bin/bash
# 3/3
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat >> "app/src/main/java/com/loopy/app/editor/EditorScreen.kt" << 'LOOPY_EOF'
@Composable
private fun RowScope.CaptureButton(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(50.dp)
            .neu(Color(0xFF2A2E3A), fill = bg, corner = 15.dp, offset = 2.6.dp, blur = 6.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun RowScope.EditToolButton(kind: String, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(52.dp)
            .neu(NeuBase, corner = 14.dp, offset = 2.6.dp, blur = 5.5.dp)
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
                    corner = 7.dp, offset = 1.6.dp, blur = 3.2.dp)
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
                val (rx, ry) = Coords.rotate(smp.nx, smp.ny, rotDeg)
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
            val (rx, ry) = Coords.rotate(nx, ny, rotDeg)
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
            val sRot = if (s.rotation >= 0) s.rotation else StrokeOps.rotationAt(macro, s.startMs)
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
            val cur = StrokeOps.sampleAt(samples, revealT)
            if (cur != null) {
                val p = mapPt(cur.first, cur.second, sRot)
                if (pressing) drawTouchDot(p, edge, cStart, rgb)
                else drawCircle(cEnd.copy(alpha = 0.4f * edge), radius = 6f, center = p)
            }
        }
    }
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
LOOPY_EOF
echo "3/3 완료."
git add -A
git commit -m "Phase 0-4: 플레이리스트 소멸(빌드가 흡수), 오버레이 재생을 Material 엔진으로, 탭 3개(대시보드/라이브러리/설정), StrokeOps 추출"
git push
echo "푸시 완료"

