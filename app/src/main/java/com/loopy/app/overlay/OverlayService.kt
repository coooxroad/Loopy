package com.loopy.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.loopy.app.input.RawRecorder
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.TouchDevice
import com.loopy.app.macro.Macro
import com.loopy.app.macro.MacroStore
import com.loopy.app.macro.Stroke
import com.loopy.app.macro.Playlist
import com.loopy.app.macro.PlaylistStore
import com.loopy.app.service.LoopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 매크로/플레이리스트 컨트롤 오버레이.
 *  - 녹화: getevent → RawRecorder(좌표 타임라인). 정지 시 자동 저장.
 *  - 재생: 저장 매크로 하나, 또는 플레이리스트(셔플백 + N회/무한)를 injectInputEvent 로 주입.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private val reader = GeteventReader()
    private val recorder = RawRecorder()
    private var device: TouchDevice? = null
    private var recording = false
    private var playJob: Job? = null

    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var recordBtn: Button
    private lateinit var stopPlayBtn: TextView
    private var listPanel: LinearLayout? = null

    private val displayObj by lazy {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        LoopyService.bind(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        recorder.shouldIgnore = { u, v -> barContains(u, v) }
        addControlBar()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun addControlBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = pill(0xF2FFFFFF.toInt(), dp(18))
            elevation = dp(6).toFloat()
        }
        val title = TextView(this).apply {
            text = "Loopy"; setTextColor(0xFF2B2D42.toInt()); textSize = 15f
        }
        status = TextView(this).apply {
            setTextColor(0xFF8A8DA0.toInt()); textSize = 11f; text = "녹화를 눌러 시작"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordBtn = pillButton("● 녹화", 0xFFFF7A6E.toInt()) { toggleRecord() }
        val playBtn = pillButton("▶ 재생", 0xFF6C7BFF.toInt()) { playRecorded() }
        val listBtn = pillButton("📁", 0xFFECECF2.toInt(), 0xFF2B2D42.toInt()) { toggleList() }
        val mtBtn = pillButton("✌", 0xFFB5E2FA.toInt(), 0xFF2B2D42.toInt()) { mtTest() }
        row.addView(recordBtn)
        row.addView(playBtn, marginLeft(dp(8)))
        row.addView(listBtn, marginLeft(dp(8)))
        row.addView(mtBtn, marginLeft(dp(8)))

        stopPlayBtn = TextView(this).apply {
            text = "■ 재생 정지"; setTextColor(0xFFFF5A4E.toInt()); textSize = 12f
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
            setOnClickListener { stopPlayback("정지됨") }
        }
        val closeBtn = TextView(this).apply {
            text = "닫기"; setTextColor(0xFF8A8DA0.toInt()); textSize = 11f
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { stopSelf() }
        }

        bar.addView(title)
        bar.addView(status)
        bar.addView(row)
        bar.addView(stopPlayBtn)
        bar.addView(closeBtn)

        barParams = baseParams().apply { x = dp(12); y = dp(60) }
        makeDraggable(title)
        wm.addView(bar, barParams)
    }

    private fun pillButton(label: String, bg: Int, fg: Int = 0xFFFFFFFF.toInt(), onClick: () -> Unit) =
        Button(this).apply {
            text = label; setTextColor(fg); background = pill(bg, dp(12))
            setOnClickListener { onClick() }
        }

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

    // ── MT-0: 두 손가락 동시 탭 테스트 ──
    private fun mtTest() {
        scope.launch {
            val m = DisplayMetrics()
            displayObj.getRealMetrics(m)
            val w = m.widthPixels
            val h = m.heightPixels
            status.text = "✌ 두 지점 동시 탭 주입 중…"
            val res = withContext(Dispatchers.IO) {
                LoopyService.twoFingerTapTest((w * 0.3).toInt(), h / 2, (w * 0.7).toInt(), h / 2)
            }
            status.text = if (res == null) "서비스 미연결" else "MT: $res"
        }
    }

    // ── 녹화 ──
    private fun toggleRecord() {
        if (!recording) startRecord() else stopRecord()
    }

    private fun startRecord() {
        stopPlayback(null)
        val devs = reader.probe()
        val dev = devs.firstOrNull { it.name.contains("touchscreen", true) } ?: devs.firstOrNull()
        if (dev == null) { status.text = "터치 디바이스를 못 찾음"; return }
        device = dev
        recorder.reset()
        recording = true
        recordBtn.text = "■ 정지"
        status.text = "● 녹화중 — 평소처럼 플레이해"
        reader.stream(scope, listOf(dev)) { _, p -> recorder.onPoint(p) }
    }

    private fun stopRecord() {
        reader.stop()
        recording = false
        recordBtn.text = "● 녹화"
        val snap = recorder.snapshot()
        if (snap.isEmpty()) { status.text = "행동 없음 (저장 안 함)"; return }
        val m = MacroStore.saveNew(this, snap)
        status.text = "저장됨: ${m.name} · ${snap.size}개"
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
    private suspend fun runStrokes(strokes: List<Stroke>) {
        for (s in strokes) {
            if (!coroutineContext.isActive) return
            delay(s.delayMs)
            val m = DisplayMetrics()
            displayObj.getRealMetrics(m)
            val w = m.widthPixels
            val h = m.heightPixels
            val rot = displayObj.rotation
            val n = s.samples.size
            if (n == 0) continue
            val xs = IntArray(n); val ys = IntArray(n); val times = LongArray(n)
            for (i in 0 until n) {
                val (px, py) = toPx(s.samples[i].nx, s.samples[i].ny, w, h, rot)
                xs[i] = px; ys[i] = py; times[i] = s.samples[i].t
            }
            withContext(Dispatchers.IO) { LoopyService.playStroke(xs, ys, times, s.durationMs) }
        }
    }

    // ── 저장 목록 (드롭다운: 플레이리스트 + 매크로) ──
    private fun toggleList() {
        listPanel?.let { bar.removeView(it); listPanel = null; return }
        val playlists = PlaylistStore.list(this)
        val macros = MacroStore.list(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0)
        }
        if (playlists.isEmpty() && macros.isEmpty()) {
            panel.addView(hint("저장된 게 없어"))
        } else {
            if (playlists.isNotEmpty()) {
                panel.addView(hint("─ 플레이리스트 ─"))
                for (pl in playlists) {
                    panel.addView(listRow("▶▶ ${pl.name} (${pl.macroIds.size})", 0xFF6C7BFF.toInt()) {
                        toggleList(); playPlaylist(pl)
                    })
                }
            }
            if (macros.isNotEmpty()) {
                panel.addView(hint("─ 매크로 ─"))
                for (mac in macros) {
                    panel.addView(listRow("▶ ${mac.name} (${mac.strokes.size})", 0xFF2B2D42.toInt()) {
                        toggleList(); startSingle(mac.strokes, mac.name)
                    })
                }
            }
        }
        bar.addView(panel)
        listPanel = panel
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF8A8DA0.toInt()); textSize = 10f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun listRow(t: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(color); textSize = 12f
        setPadding(0, dp(7), 0, dp(7))
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

    private fun makeDraggable(handle: View) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = barParams.x; startY = barParams.y; touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    barParams.x = startX + (e.rawX - touchX).toInt()
                    barParams.y = startY + (e.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(bar, barParams) }; true
                }
                else -> false
            }
        }
    }

    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }

    private fun startAsForeground() {
        val channelId = "loopy_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Loopy 오버레이", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Loopy 실행 중")
            .setContentText("매크로 컨트롤이 화면에 떠 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reader.stop()
        scope.cancel()
        runCatching { wm.removeView(bar) }
    }
}
