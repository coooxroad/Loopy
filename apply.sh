#!/data/data/com.termux/files/usr/bin/bash
# 편집기 UI개편 2/2 (이어붙임)
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat >> "app/src/main/java/com/loopy/app/editor/EditorScreen.kt" << 'LOOPY_EOF'
            // 편집공간: 필름스트립 타임라인
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
        }
    }
}

@Composable
private fun PlayPauseOutline(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val c = TextHi
            val sw = 2.6f
            if (playing) {
                val bw = size.width * 0.20f
                val gap = size.width * 0.20f
                val h = size.height * 0.78f
                val top = (size.height - h) / 2f
                val x1 = size.width / 2f - gap / 2f - bw
                val x2 = size.width / 2f + gap / 2f
                drawRect(c, topLeft = Offset(x1, top),
                    size = androidx.compose.ui.geometry.Size(bw, h), style = DrawStroke(sw))
                drawRect(c, topLeft = Offset(x2, top),
                    size = androidx.compose.ui.geometry.Size(bw, h), style = DrawStroke(sw))
            } else {
                val p = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.12f)
                    lineTo(size.width * 0.86f, size.height * 0.5f)
                    lineTo(size.width * 0.22f, size.height * 0.88f)
                    close()
                }
                drawPath(p, c, style = DrawStroke(width = sw, join = StrokeJoin.Round))
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
    val trackH = 58.dp
    val rulerH = 18.dp
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

    BoxWithConstraints(modifier.background(NeuBase)) {
        val viewportPx = constraints.maxWidth
        val halfPx = viewportPx / 2f
        val contentPx = viewportPx + totalMs * pxPerMs
        val contentDp = with(density) { contentPx.toDp() }
        val halfDp = with(density) { halfPx.toDp() }

        Box(Modifier.fillMaxSize().padding(top = 8.dp), contentAlignment = Alignment.TopStart) {
            Column(Modifier.fillMaxWidth().horizontalScroll(scrollState).width(contentDp)) {
                Canvas(Modifier.fillMaxWidth().height(rulerH)) {
                    val yb = size.height
                    for (sec in 0..secCount) {
                        val x = halfPx + sec * 1000f * pxPerMs
                        drawLine(CardStroke, Offset(x, yb * 0.4f), Offset(x, yb), strokeWidth = 2f)
                    }
                }
                Row(Modifier.height(trackH)) {
                    Spacer(Modifier.width(halfDp))
                    for (i in 0 until secCount) {
                        val ib = thumbs.getOrNull(i)
                        Box(
                            Modifier.width(dpPerSec.dp).height(trackH)
                                .background(Color(0xFF1A1D26))
                                .border(0.5.dp, Color(0x22000000)),
                        ) {
                            if (ib != null) {
                                Image(ib, contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                    }
                    Spacer(Modifier.width(halfDp))
                }
            }
            // 중앙 재생헤드(고정)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.width(2.dp).height(rulerH + trackH).background(Accent))
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
LOOPY_EOF
echo "2/2 완료."
git add -A
git commit -m "편집기 UI: 검은 영상 직사각형+인포바(선심볼 재생)+그라데이션 구분+크롭 필름스트립, 스트로크블록/툴바 제거"
git push
echo "푸시 완료!"

