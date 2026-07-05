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
import com.loopy.app.input.GestureRecorder
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.TouchDevice
import com.loopy.app.macro.MacroStore
import com.loopy.app.service.LoopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 매크로 컨트롤 오버레이.
 *  - 녹화: getevent → GestureRecorder(탭/홀드/스와이프). 정지 시 자동 저장(날짜시간 이름).
 *  - 재생: '지금 녹화' 또는 📁 목록에서 고른 저장 매크로를 injectInputEvent 로 주입.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private val reader = GeteventReader()
    private val recorder = GestureRecorder()
    private var device: TouchDevice? = null
    private var recording = false

    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var recordBtn: Button
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
            text = "Loopy"
            setTextColor(0xFF2B2D42.toInt())
            textSize = 15f
        }
        status = TextView(this).apply {
            setTextColor(0xFF8A8DA0.toInt())
            textSize = 11f
            text = "녹화를 눌러 시작"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordBtn = pillButton("● 녹화", 0xFFFF7A6E.toInt()) { toggleRecord() }
        val playBtn = pillButton("▶ 재생", 0xFF6C7BFF.toInt()) { playRecorded() }
        val listBtn = pillButton("📁", 0xFFECECF2.toInt(), 0xFF2B2D42.toInt()) { toggleList() }
        row.addView(recordBtn)
        row.addView(playBtn, marginLeft(dp(8)))
        row.addView(listBtn, marginLeft(dp(8)))
        val closeBtn = TextView(this).apply {
            text = "닫기"
            setTextColor(0xFF8A8DA0.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { stopSelf() }
        }

        bar.addView(title)
        bar.addView(status)
        bar.addView(row)
        bar.addView(closeBtn)

        barParams = baseParams().apply { x = dp(12); y = dp(60) }
        makeDraggable(title)
        wm.addView(bar, barParams)
    }

    private fun pillButton(
        label: String, bg: Int, fg: Int = 0xFFFFFFFF.toInt(), onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        setTextColor(fg)
        background = pill(bg, dp(12))
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

    // ── 녹화 ──
    private fun toggleRecord() {
        if (!recording) startRecord() else stopRecord()
    }

    private fun startRecord() {
        val devs = reader.probe()
        val dev = devs.firstOrNull { it.name.contains("touchscreen", true) } ?: devs.firstOrNull()
        if (dev == null) {
            status.text = "터치 디바이스를 못 찾음"
            return
        }
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
        if (snap.isEmpty()) {
            status.text = "행동 없음 (저장 안 함)"
            return
        }
        val m = MacroStore.saveNew(this, snap)
        status.text = "저장됨: ${m.name} · ${snap.size}개"
    }

    // ── 재생 ──
    private fun playRecorded() {
        if (recording) stopRecord()
        playActions(recorder.snapshot(), "지금 녹화")
    }

    private fun playActions(list: List<GestureRecorder.Action>, label: String) {
        if (list.isEmpty()) {
            status.text = "재생할 행동이 없어"
            return
        }
        status.text = "▶ 재생중… $label (${list.size})"
        scope.launch {
            val m = DisplayMetrics()
            displayObj.getRealMetrics(m)
            val w = m.widthPixels
            val h = m.heightPixels
            val rot = displayObj.rotation
            for (a in list) {
                delay(a.delayMs)
                val (x, y) = toPx(a.x, a.y, w, h, rot)
                withContext(Dispatchers.IO) {
                    when (a.type) {
                        GestureRecorder.Type.TAP -> LoopyService.tap(x, y)
                        GestureRecorder.Type.HOLD -> LoopyService.hold(x, y, a.durationMs.toInt())
                        GestureRecorder.Type.SWIPE -> {
                            val (x2, y2) = toPx(a.x2, a.y2, w, h, rot)
                            LoopyService.swipe(x, y, x2, y2, a.durationMs.toInt().coerceAtLeast(50))
                        }
                    }
                }
            }
            status.text = "재생 끝 · $label"
        }
    }

    // ── 저장 목록 (드롭다운) ──
    private fun toggleList() {
        listPanel?.let {
            bar.removeView(it)
            listPanel = null
            return
        }
        val macros = MacroStore.list(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        if (macros.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "저장된 매크로 없음"
                setTextColor(0xFF8A8DA0.toInt()); textSize = 11f
            })
        } else {
            for (mac in macros) {
                panel.addView(TextView(this).apply {
                    text = "▶ ${mac.name}  (${mac.actions.size})"
                    setTextColor(0xFF2B2D42.toInt())
                    textSize = 12f
                    setPadding(0, dp(7), 0, dp(7))
                    setOnClickListener {
                        toggleList()
                        playActions(mac.actions, mac.name)
                    }
                })
            }
        }
        bar.addView(panel)
        listPanel = panel
    }

    /** panel 정규화(u,v) → 현재 방향의 화면 픽셀. */
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
        val rect = Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height)
        return rect.contains(px, py)
    }

    private fun makeDraggable(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = barParams.x; startY = barParams.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    barParams.x = startX + (e.rawX - touchX).toInt()
                    barParams.y = startY + (e.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(bar, barParams) }
                    true
                }
                else -> false
            }
        }
    }

    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
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
