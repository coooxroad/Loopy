package com.loopy.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
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
import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 화면 위 오버레이 컨트롤 (탭 테스트용, 나중에 매크로 컨트롤로 확장).
 *
 * 구성:
 *  - 조준점(십자): 드래그해서 원하는 위치로 옮긴다.
 *  - 컨트롤 바: "여기 탭"(조준점 위치를 input tap), "닫기"(서비스 종료). 역시 드래그 가능.
 *
 * input tap 은 조준점 중심의 화면 픽셀 좌표로 주입한다. IO 스레드에서 실행(ANR 방지).
 * 좌표계: TYPE_APPLICATION_OVERLAY + FLAG_LAYOUT_NO_LIMITS 라 params.x/y 는
 * 디스플레이 좌상단 기준 픽셀 → input tap 의 좌표계와 맞는다.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private lateinit var crosshair: CrosshairView
    private lateinit var crosshairParams: WindowManager.LayoutParams
    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var coordLabel: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addCrosshair()
        addControlBar()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun baseParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

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
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = pill(0xE61A1A24.toInt(), dp(16))
        }
        val title = TextView(this).apply {
            text = "Loopy 탭 테스트"
            setTextColor(0xFFF3F3F7.toInt())
            textSize = 13f
        }
        coordLabel = TextView(this).apply {
            setTextColor(0xFF9A9AB0.toInt())
            textSize = 11f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tapBtn = Button(this).apply {
            text = "여기 탭"
            setOnClickListener { tapAtCrosshair() }
        }
        val closeBtn = Button(this).apply {
            text = "닫기"
            setOnClickListener { stopSelf() }
        }
        row.addView(tapBtn)
        row.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })

        bar.addView(title)
        bar.addView(coordLabel)
        bar.addView(row)

        barParams = baseParams().apply {
            x = dp(16); y = dp(60)
        }
        // 제목/좌표 영역을 잡고 드래그 (버튼은 클릭 우선)
        makeDraggable(title, barParams) {}
        makeDraggable(coordLabel, barParams) {}
        wm.addView(bar, barParams)
        updateCoordLabel()
    }

    private fun tapAtCrosshair() {
        val cx = crosshairParams.x + crosshair.width / 2
        val cy = crosshairParams.y + crosshair.height / 2
        coordLabel.text = "탭: ($cx, $cy) …"
        scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                Shell.exec("input tap $cx $cy")
            }
            coordLabel.text = "탭 완료: ($cx, $cy)"
        }
    }

    private fun updateCoordLabel() {
        val cx = crosshairParams.x + crosshair.width / 2
        val cy = crosshairParams.y + crosshair.height / 2
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
                    wm.updateViewLayout(root, params)
                    onMoved()
                    true
                }
                else -> false
            }
        }
    }

    private fun pill(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
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

    /** 반투명 링 + 십자 + 중심점을 그리는 조준점 뷰. */
    class CrosshairView(context: Context, private val sizePx: Int) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.06f
            color = 0x807C5CFF.toInt()
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.04f
            color = 0xCC7C5CFF.toInt()
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF7C5CFF.toInt()
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
