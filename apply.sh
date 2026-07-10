#!/data/data/com.termux/files/usr/bin/bash
# 편집기 타임라인 2/2 (이어붙임)
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat >> "app/src/main/java/com/loopy/app/editor/EditorScreen.kt" << 'LOOPY_EOF'
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
LOOPY_EOF
echo "2/2 완료."
git add -A
git commit -m "편집기 실제 타임라인: 몰입모드+실시간seek+프레임 필름스트립+중앙 재생헤드+스트로크 트랙"
git push
echo "푸시 완료!"

