package com.loopy.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.loopy.app.service.LoopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 화면 위 오버레이 컨트롤 (탭 테스트용, 나중에 매크로 컨트롤로 확장).
 *
 * 핵심: 조준점은 터치 가능한 오버레이 창이라, input tap 을 조준점 위치에 쏘면
 * 게임이 아니라 조준점이 그 탭을 가로챈다. 그래서 주입하는 순간에만 조준점을
 * FLAG_NOT_TOUCHABLE 로 바꿔 탭이 아래(게임)로 통과하게 한다.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private lateinit var crosshair: CrosshairView
    private lateinit var crosshairParams: WindowManager.LayoutParams
    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var coordLabel: TextView
    private val locBuf = IntArray(2)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        LoopyService.bind(this) // injectInputEvent 엔진 연결
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addCrosshair()
        addControlBar()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    // ── 조준점 ──
    private fun addCrosshair() {
        crosshair = CrosshairView(this, dp(72))
        crosshairParams = baseParams().apply {
            width = dp(72); height = dp(72)
            x = dp(140); y = dp(300)
        }
        makeDraggable(crosshair, crosshairParams) { updateCoordLabel() }
        wm.addView(crosshair, crosshairParams)
    }

    // ── 컨트롤 바 ──
    private fun addControlBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = pill(0xF2FFFFFF.toInt(), dp(18))
            elevation = dp(6).toFloat()
        }
        val title = TextView(this).apply {
            text = "Loopy 탭 테스트"
            setTextColor(0xFF2B2D42.toInt())
            textSize = 13f
        }
        coordLabel = TextView(this).apply {
            setTextColor(0xFF8A8DA0.toInt())
            textSize = 11f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tapBtn = Button(this).apply {
            text = "여기 탭"
            setTextColor(0xFFFFFFFF.toInt())
            background = pill(0xFF6C7BFF.toInt(), dp(12))
            setOnClickListener { tapAtCrosshair() }
        }
        val closeBtn = Button(this).apply {
            text = "닫기"
            setTextColor(0xFF2B2D42.toInt())
            background = pill(0xFFECECF2.toInt(), dp(12))
            setOnClickListener { stopSelf() }
        }
        row.addView(tapBtn)
        row.addView(
            closeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )

        bar.addView(title)
        bar.addView(coordLabel)
        bar.addView(row)

        barParams = baseParams().apply { x = dp(16); y = dp(60) }
        makeDraggable(title, barParams) {}
        makeDraggable(coordLabel, barParams) {}
        wm.addView(bar, barParams)
        updateCoordLabel()
    }

    /**
     * 조준점 중심의 "실제 화면 픽셀 좌표". getLocationOnScreen 은 상태바/노치를 포함한
     * 물리 화면 기준이라 input tap 의 좌표계와 정확히 일치한다. (params.x/y 는 상태바를
     * 제외한 영역 기준이라 회전 시 상태바 크기만큼 어긋났던 원인.)
     */
    private fun crosshairCenter(): Pair<Int, Int> {
        crosshair.getLocationOnScreen(locBuf)
        return (locBuf[0] + crosshair.width / 2) to (locBuf[1] + crosshair.height / 2)
    }

    private fun tapAtCrosshair() {
        val (cx, cy) = crosshairCenter()
        coordLabel.text = "탭 주입: ($cx, $cy)…"
        setCrosshairTouchable(false) // 조준점이 탭을 가로채지 않게
        scope.launch {
            delay(60)
            val ok = withContext(Dispatchers.IO) { LoopyService.tap(cx, cy) }
            setCrosshairTouchable(true)
            coordLabel.text = if (ok) "탭 완료: ($cx, $cy)"
            else "서비스 미연결 — 앱에서 Shizuku 확인 후 오버레이 재실행"
        }
    }

    private fun setCrosshairTouchable(touchable: Boolean) {
        crosshairParams.flags = if (touchable) {
            crosshairParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            crosshairParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(crosshair, crosshairParams) }
    }

    private fun updateCoordLabel() {
        val (cx, cy) = crosshairCenter()
        coordLabel.text = "조준점: ($cx, $cy)"
    }

    /** 뷰를 드래그하면 해당 window 의 x/y 를 갱신. */
    private fun makeDraggable(
        handle: View,
        params: WindowManager.LayoutParams,
        onMoved: () -> Unit,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    val root = if (v === crosshair) crosshair else bar
                    runCatching { wm.updateViewLayout(root, params) }
                    onMoved()
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
            .setContentTitle("Loopy 오버레이 실행 중")
            .setContentText("탭 테스트 컨트롤이 화면에 떠 있어요")
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
        scope.cancel()
        runCatching { wm.removeView(crosshair) }
        runCatching { wm.removeView(bar) }
    }

    /** 반투명 링 + 십자 + 중심점을 그리는 조준점 뷰 (페리윙클). */
    class CrosshairView(context: Context, private val sizePx: Int) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.06f
            color = 0x806C7BFF.toInt()
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.04f
            color = 0xCC6C7BFF.toInt()
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF6C7BFF.toInt()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }

        override fun onDraw(canvas: Canvas) {
            val c = sizePx / 2f
            val r = sizePx * 0.42f
            canvas.drawCircle(c, c, r, ring)
            canvas.drawLine(c, c - r, c, c + r, cross)
            canvas.drawLine(c - r, c, c + r, c, cross)
            canvas.drawCircle(c, c, sizePx * 0.06f, dot)
        }
    }
}
