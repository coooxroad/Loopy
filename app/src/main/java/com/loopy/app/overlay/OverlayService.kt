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
import android.widget.Toast
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordBtn = pillButton("● 녹화", 0xFFFF7A6E.toInt()) { toggleRecord() }
        val playBtn = pillButton("▶ 재생", 0xFF6C7BFF.toInt()) { playRecorded() }
        row1.addView(recordBtn)
        row1.addView(playBtn, marginLeft(dp(8)))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val listBtn = pillButton("📁 목록", 0xFFECECF2.toInt(), 0xFF2B2D42.toInt()) { toggleList() }
        val mtBtn = pillButton("✌ MT", 0xFFB5E2FA.toInt(), 0xFF2B2D42.toInt()) { mtTest() }
        row2.addView(listBtn)
        row2.addView(mtBtn, marginLeft(dp(8)))

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mt1Btn = pillButton("◎ MT1 (두 원)", 0xFFCDDAFD.toInt(), 0xFF2B2D42.toInt()) { mt1Test() }
        row3.addView(mt1Btn)

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
        bar.addView(row1)
        bar.addView(row2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        bar.addView(row3, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
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
        Toast.makeText(this, "MT 테스트 시작", Toast.LENGTH_SHORT).show()
        status.text = "✌ 시작…"
        scope.launch {
            val msg = try {
                val m = DisplayMetrics()
                displayObj.getRealMetrics(m)
                val w = m.widthPixels
                val h = m.heightPixels
                val res = withContext(Dispatchers.IO) {
                    LoopyService.twoFingerTapTest((w * 0.3).toInt(), h / 2, (w * 0.7).toInt(), h / 2)
                }
                if (res == null) "서비스 미연결" else "MT: $res"
            } catch (t: Throwable) {
                "MT 예외: ${t.javaClass.simpleName}: ${(t.message ?: "").take(60)}"
            }
            status.text = msg
            Toast.makeText(this@OverlayService, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ── MT-1: 두 원을 동시에 그리기 (동시 재생 엔진 검증) ──
    private fun mt1Test() {
        Toast.makeText(this, "MT1: 두 원 동시 그리기", Toast.LENGTH_SHORT).show()
        scope.launch {
            val msg = try {
                val m = DisplayMetrics()
                displayObj.getRealMetrics(m)
                val w = m.widthPixels
                val h = m.heightPixels
                val n = 40
                val dur = 1600L
                val r = (w * 0.12).toInt()
                val cx0 = (w * 0.3).toInt(); val cy0 = h / 2
                val cx1 = (w * 0.7).toInt(); val cy1 = h / 2
                val xs = IntArray(n * 2); val ys = IntArray(n * 2); val times = LongArray(n * 2)
                for (i in 0 until n) {
                    val ang = 2 * PI * i / n
                    val t = i.toLong() * dur / n
                    xs[i] = (cx0 + r * cos(ang)).toInt(); ys[i] = (cy0 + r * sin(ang)).toInt(); times[i] = t
                    xs[n + i] = (cx1 + r * cos(ang)).toInt(); ys[n + i] = (cy1 + r * sin(ang)).toInt(); times[n + i] = t
                }
                val ok = withContext(Dispatchers.IO) {
                    LoopyService.playMulti(
                        intArrayOf(0, 1), longArrayOf(0L, 0L), longArrayOf(dur, dur),
                        intArrayOf(n, n), xs, ys, times,
                    )
                }
                if (ok) "MT1 재생됨 — 십자선 2개가 각각 원을 그려야 함" else "서비스 미연결"
            } catch (t: Throwable) {
                "MT1 예외: ${t.javaClass.simpleName}: ${(t.message ?: "").take(50)}"
            }
            status.text = msg
            Toast.makeText(this@OverlayService, msg, Toast.LENGTH_LONG).show()
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
                if (idFreeAt[id] <= s.startMs) { assigned = id; break }
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
