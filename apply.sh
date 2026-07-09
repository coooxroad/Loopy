#!/data/data/com.termux/files/usr/bin/bash
# OverlayService 복구 2/2 (뒷부분, 이어붙임)
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat >> "app/src/main/java/com/loopy/app/overlay/OverlayService.kt" << 'LOOPY_EOF'
    private fun marginLeft(px: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = px }

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    // ── 녹화 ──
    private fun videoTint() = if (videoEnabled) 0xFF20C997.toInt() else 0xFF9AA0B4.toInt()
    private fun videoBg() = if (videoEnabled) 0x2220C997 else 0x14000000

    private fun toggleVideo() {
        videoEnabled = !videoEnabled
        videoBtn?.apply {
            setColorFilter(videoTint())
            background = circleBg(videoBg())
        }
        status.visibility = View.VISIBLE
        status.text = if (videoEnabled) "영상 녹화 ON (녹화 시 화면도 저장)" else "영상 녹화 OFF"
    }

    private fun toggleRecord() {
        if (recording) { stopRecord(); return }
        if (videoEnabled) {
            if (screenRecorder.hasSession()) {
                // 세션 재사용 — 팝업/앱전환 없이 즉시
                val ok = screenRecorder.startRecording()
                startRecord(if (ok) screenRecorder.currentUri else null)
            } else {
                // 세션 없음 → 예외적으로 팝업 1회(이후 세션으로 유지됨)
                val i = Intent(this, com.loopy.app.ProjectionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        } else {
            startRecord(null)
        }
    }

    /** 세션 없을 때 팝업 폴백: 받은 권한을 세션으로 승격 후 녹화(다음부턴 팝업 없음). */
    private fun beginVideoThenRecord(intent: Intent) {
        val code = intent.getIntExtra(EX_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EX_DATA)
        if (code != Activity.RESULT_OK || data == null) { startRecord(null); return }
        runCatching { promoteForegroundForProjection() }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (proj == null) { startRecord(null); return }
        screenRecorder.setSession(proj)
        VideoSession.active = true
        val ok = screenRecorder.startRecording()
        startRecord(if (ok) screenRecorder.currentUri else null)
    }

    private fun promoteForegroundForProjection() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1, buildNotif(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        }
    }

    private fun startRecord(videoPath: String?) {
        stopPlayback(null)
        val devs = reader.probe()
        val dev = devs.firstOrNull { it.name.contains("touchscreen", true) } ?: devs.firstOrNull()
        if (dev == null) { status.text = "터치 디바이스를 못 찾음"; return }
        device = dev
        currentVideoPath = videoPath
        recorder.reset()
        recording = true
        recordBtn.setImageResource(R.drawable.ic_ov_stop)
        status.visibility = View.VISIBLE
        status.text = if (videoPath != null) "● 녹화중 (영상 포함)" else "● 녹화중 — 평소처럼 플레이해"
        reader.stream(scope, listOf(dev)) { _, p -> recorder.onPoint(p) }
    }

    private fun stopRecord() {
        reader.stop()
        recording = false
        recordBtn.setImageResource(R.drawable.ic_ov_record)
        val videoStart = if (screenRecorder.active) screenRecorder.startUptime else 0L
        val vpath = if (screenRecorder.active) screenRecorder.stopRecording() else currentVideoPath
        currentVideoPath = null
        val snap = recorder.snapshot()
        if (snap.isEmpty()) {
            status.text = "행동 없음 (저장 안 함)"
            return
        }
        val offset = if (vpath != null && videoStart > 0 && recorder.baseUptime > 0)
            (recorder.baseUptime - videoStart).coerceAtLeast(0L) else 0L
        val m = MacroStore.saveNew(this, snap, vpath, offset)
        status.text = "저장됨: ${m.name} · ${snap.size}개" + if (vpath != null) " · 영상" else ""
    }

    // ── 재생 ──
    private fun playRecorded() {
        if (recording) stopRecord()
        startSingle(recorder.snapshot(), "지금 녹화")
    }

    private fun startSingle(strokes: List<Stroke>, label: String) {
        if (recording) stopRecord()
        stopPlayback(null)
        if (strokes.isEmpty()) { status.text = "재생할 게 없어"; return }
        stopPlayBtn.visibility = View.VISIBLE
        status.text = "▶ 재생중… $label"
        playJob = scope.launch {
            runStrokes(strokes)
            status.text = "재생 끝 · $label"
            stopPlayBtn.visibility = View.GONE
            playJob = null
        }
    }

    private fun playPlaylist(pl: Playlist) {
        if (recording) stopRecord()
        stopPlayback(null)
        val macros = HashMap<String, Macro>()
        pl.macroIds.toSet().forEach { id -> MacroStore.read(this, id)?.let { macros[id] = it } }
        if (macros.isEmpty()) { status.text = "매크로가 비어있어"; return }
        stopPlayBtn.visibility = View.VISIBLE
        playJob = scope.launch {
            var cycle = 0
            while (isActive && (pl.cycles == 0 || cycle < pl.cycles)) {
                val order = if (pl.shuffle) pl.macroIds.shuffled() else pl.macroIds
                for (id in order) {
                    if (!isActive) break
                    val m = macros[id] ?: continue
                    val total = if (pl.cycles > 0) "/${pl.cycles}" else ""
                    status.text = "▶ ${pl.name} · ${cycle + 1}$total · ${m.name}"
                    runStrokes(m.strokes)
                    if (pl.gapMs > 0) delay(pl.gapMs.toLong())
                }
                cycle++
            }
            status.text = "플레이리스트 끝 · ${pl.name}"
            stopPlayBtn.visibility = View.GONE
            playJob = null
        }
    }

    private fun stopPlayback(msg: String?) {
        playJob?.cancel()
        playJob = null
        stopPlayBtn.visibility = View.GONE
        if (msg != null) status.text = msg
    }

    /** 스트로크들을 현재 화면 방향에 맞춰 순차 주입. 취소 가능. */
    /** 모든 스트로크를 절대 시각(startMs) 기준으로 병합해 playMulti 로 한 번에 동시 재생. */
    private suspend fun runStrokes(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        val m = DisplayMetrics()
        displayObj.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels
        val rot = displayObj.rotation

        val nStroke = strokes.size
        // 손가락 id 배정: 겹치지 않는 스트로크끼리는 같은 id 재사용(활성 포인터 수 최소화).
        val fingerIds = IntArray(nStroke)
        val idFreeAt = ArrayList<Long>()
        for (k in strokes.indices) {
            val s = strokes[k]
            val end = s.startMs + maxOf(s.durationMs, s.samples.lastOrNull()?.t ?: 0L)
            var assigned = -1
            for (id in idFreeAt.indices) {
                if (idFreeAt[id] + 12L <= s.startMs) { assigned = id; break }
            }
            if (assigned == -1) { assigned = idFreeAt.size; idFreeAt.add(end) } else idFreeAt[assigned] = end
            fingerIds[k] = assigned
        }

        val totalSamples = strokes.sumOf { it.samples.size }
        val startArr = LongArray(nStroke)
        val durArr = LongArray(nStroke)
        val counts = IntArray(nStroke)
        val xs = IntArray(totalSamples)
        val ys = IntArray(totalSamples)
        val times = LongArray(totalSamples)
        var off = 0
        for (k in strokes.indices) {
            val s = strokes[k]
            startArr[k] = s.startMs
            durArr[k] = s.durationMs
            counts[k] = s.samples.size
            for (i in s.samples.indices) {
                val (px, py) = toPx(s.samples[i].nx, s.samples[i].ny, w, h, rot)
                xs[off] = px; ys[off] = py; times[off] = s.samples[i].t
                off++
            }
        }
        withContext(Dispatchers.IO) {
            LoopyService.playMulti(fingerIds, startArr, durArr, counts, xs, ys, times)
        }
    }

    // ── 저장 목록 (드롭다운: 플레이리스트 + 매크로) ──
    private fun toggleList() {
        listPanel?.let { listHolder.removeView(it); listPanel = null; return }
        val playlists = PlaylistStore.list(this)
        val macros = MacroStore.list(this)
        val lp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = pill(0xFFFFFFFF.toInt(), dp(18))
            elevation = dp(8).toFloat()
            minimumWidth = dp(210)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (playlists.isEmpty() && macros.isEmpty()) {
            content.addView(hint("저장된 게 없어"))
        } else {
            if (playlists.isNotEmpty()) {
                content.addView(hint("─ 플레이리스트 ─"))
                for (pl in playlists) {
                    content.addView(listRow("${pl.name}  ·  ${pl.macroIds.size}스텝", 0xFF6C7BFF.toInt()) {
                        toggleList(); playPlaylist(pl)
                    })
                }
            }
            if (macros.isNotEmpty()) {
                content.addView(hint("─ 매크로 ─"))
                for (mac in macros) {
                    content.addView(listRow("${mac.name}  ·  ${mac.strokes.size}", 0xFF2B2D42.toInt()) {
                        toggleList(); startSingle(mac.strokes, mac.name)
                    })
                }
            }
        }
        // 화면 절반 넘으면 스크롤
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val sv = MaxHeightScrollView(this, maxH).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        sv.addView(content)
        lp.addView(sv)
        listHolder.addView(lp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        listPanel = lp
    }

    /** 최대 높이를 넘으면 스크롤되는 ScrollView. */
    private class MaxHeightScrollView(context: Context, private val maxH: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, h)
        }
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF9AA0B4.toInt()); textSize = 10f
        setPadding(dp(2), dp(8), 0, dp(4))
        letterSpacing = 0.06f
    }

    private fun listRow(t: String, color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(9), dp(10), dp(9))
        background = pill(0x0A000000, dp(12)) // 아주 옅은 행 배경
        val dot = View(this@OverlayService).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
        }
        addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
        addView(TextView(this@OverlayService).apply {
            text = t; setTextColor(0xFF2B2D42.toInt()); textSize = 13f
            setPadding(dp(10), 0, 0, 0)
        })
        setOnClickListener { onClick() }
    }

    private fun toPx(u: Float, v: Float, w: Int, h: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> (v * w).toInt() to ((1 - u) * h).toInt()
        Surface.ROTATION_180 -> ((1 - u) * w).toInt() to ((1 - v) * h).toInt()
        Surface.ROTATION_270 -> ((1 - v) * w).toInt() to (u * h).toInt()
        else -> (u * w).toInt() to (v * h).toInt()
    }

    private fun barContains(u: Float, v: Float): Boolean {
        val m = DisplayMetrics()
        displayObj.getRealMetrics(m)
        val (px, py) = toPx(u, v, m.widthPixels, m.heightPixels, displayObj.rotation)
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height).contains(px, py)
    }


    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }

    private fun buildNotif(): Notification {
        val channelId = "loopy_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Loopy 오버레이", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Loopy 실행 중")
            .setContentText("매크로 컨트롤이 화면에 떠 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reader.stop()
        runCatching { screenRecorder.endSession() }
        VideoSession.active = false
        scope.cancel()
        dimView?.let { runCatching { wm.removeView(it) } }
        runCatching { wm.removeView(bar) }
    }
}

LOOPY_EOF
echo "2/2 완료. 파일 조립됨."
git add -A
git commit -m "fix: OverlayService 복구(세션 독립 온전판)"
git push
echo "푸시 완료!"

