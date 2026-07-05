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
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.GestureRecorder
import com.loopy.app.input.TouchDevice
import com.loopy.app.service.LoopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 매크로 컨트롤 오버레이. (조준점 탭 테스트는 제거됨)
 *
 * 녹화: getevent 로 사용자의 실제 터치를 읽어 GestureRecorder 로 탭/홀드/스와이프 판정.
 *       컨트롤 바 위의 터치는 무시(shouldIgnore).
 * 재생: 저장된 Action 을 현재 화면 방향에 맞게 픽셀로 변환해 injectInputEvent 로 주입.
 *
 * 저장(파일)은 다음 단계. 지금은 메모리에서 녹화→재생 왕복 검증.
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
    private lateinit var playBtn: Button

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

    // ── 컨트롤 바 ──
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
        recordBtn = Button(this).apply {
            text = "● 녹화"
            setTextColor(0xFFFFFFFF.toInt())
            background = pill(0xFFFF7A6E.toInt(), dp(12))
            setOnClickListener { toggleRecord() }
        }
        playBtn = Button(this).apply {
            text = "▶ 재생"
            setTextColor(0xFFFFFFFF.toInt())
            background = pill(0xFF6C7BFF.toInt(), dp(12))
            setOnClickListener { play() }
        }
        row.addView(recordBtn)
        row.addView(
            playBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )
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
        status.text = "행동 ${recorder.count()}개 · 재생 가능"
    }

    // ── 재생 ──
    private fun play() {
        if (recording) stopRecord()
        val list = recorder.snapshot()
        if (list.isEmpty()) {
            status.text = "녹화된 행동이 없어"
            return
        }
        status.text = "▶ 재생중… (${list.size}개)"
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
            status.text = "재생 끝 · 행동 ${list.size}개"
        }
    }

    /** panel 정규화(u,v) → 현재 방향의 화면 픽셀. getevent 는 회전 전 패널 좌표라 보정 필요. */
    private fun toPx(u: Float, v: Float, w: Int, h: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> (v * w).toInt() to ((1 - u) * h).toInt()
        Surface.ROTATION_180 -> ((1 - u) * w).toInt() to ((1 - v) * h).toInt()
        Surface.ROTATION_270 -> ((1 - v) * w).toInt() to (u * h).toInt()
        else -> (u * w).toInt() to (v * h).toInt()
    }

    /** panel(u,v) 가 컨트롤 바 위인지 (녹화에서 무시하려고). */
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
