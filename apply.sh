#!/data/data/com.termux/files/usr/bin/bash
# Loopy B-2: 녹화(탭/홀드/스와이프) → 재생
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

rm -f "app/src/main/java/com/loopy/app/input/InputInjector.kt"

mkdir -p "$(dirname "app/src/main/aidl/com/loopy/app/service/ILoopyService.aidl")"
cat > "app/src/main/aidl/com/loopy/app/service/ILoopyService.aidl" << 'LOOPY_EOF'
// Shizuku UserService 인터페이스. elevated(shell) 프로세스에서 실행되는 메서드들.
package com.loopy.app.service;

interface ILoopyService {
    // Shizuku 서버가 서비스를 종료할 때 호출하는 예약 트랜잭션 ID (고정).
    void destroy() = 16777114;
    void exit() = 1;

    // 화면 픽셀 좌표에 탭 주입.
    void tap(int x, int y) = 2;

    // (x1,y1) → (x2,y2) 로 durationMs 동안 스와이프.
    void swipe(int x1, int y1, int x2, int y2, int durationMs) = 3;

    // 같은 자리 더블탭. gapMs = 두 탭 사이 간격.
    void doubleTap(int x, int y, int gapMs) = 4;

    // 같은 자리에서 durationMs 동안 누르고 있기(홀드/롱프레스).
    void hold(int x, int y, int durationMs) = 5;
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/service/LoopyUserService.kt")"
cat > "app/src/main/java/com/loopy/app/service/LoopyUserService.kt" << 'LOOPY_EOF'
package com.loopy.app.service

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku UserService 본체. Shizuku 가 이 클래스를 shell(uid 2000) 프로세스에 띄운다.
 * 그 프로세스는 shell 권한이라, adb 의 `input` 명령과 동일하게 injectInputEvent 를
 * 호출할 수 있다(앱 프로세스에서는 권한이 없어 불가).
 *
 * 주입 방식은 scrcpy 와 동일:
 *  - 안드로이드 14+ 에서 일부가 InputManagerGlobal 로 옮겨졌으므로 그것을 먼저 시도,
 *    없으면 InputManager 로 폴백.
 *  - getInstance() 로 인스턴스를 얻고 injectInputEvent(InputEvent, int) 를 리플렉션 호출.
 *  - MotionEvent 의 source 를 TOUCHSCREEN 으로 지정해야 터치로 인식된다.
 */
class LoopyUserService : ILoopyService.Stub() {

    private val instance: Any
    private val injectMethod: Method

    init {
        val cls = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }
            .getOrElse { Class.forName("android.hardware.input.InputManager") }
        val getInstance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
        instance = getInstance.invoke(null)!!
        injectMethod = runCatching {
            instance.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        }.getOrElse {
            instance.javaClass.getDeclaredMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        }.apply { isAccessible = true }
    }

    private fun inject(ev: InputEvent) {
        // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        injectMethod.invoke(instance, ev, 0)
    }

    private fun motion(downTime: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return ev
    }

    private fun downUp(x: Int, y: Int) {
        val t = SystemClock.uptimeMillis()
        val down = motion(t, t, MotionEvent.ACTION_DOWN, x, y)
        inject(down); down.recycle()
        val t2 = SystemClock.uptimeMillis()
        val up = motion(t, t2, MotionEvent.ACTION_UP, x, y)
        inject(up); up.recycle()
    }

    override fun tap(x: Int, y: Int) {
        runCatching { downUp(x, y) }
    }

    override fun doubleTap(x: Int, y: Int, gapMs: Int) {
        runCatching {
            downUp(x, y)
            Thread.sleep(gapMs.toLong().coerceAtLeast(0))
            downUp(x, y)
        }
    }

    override fun hold(x: Int, y: Int, durationMs: Int) {
        runCatching {
            val t = SystemClock.uptimeMillis()
            val down = motion(t, t, MotionEvent.ACTION_DOWN, x, y)
            inject(down); down.recycle()
            Thread.sleep(durationMs.toLong().coerceAtLeast(0))
            val t2 = SystemClock.uptimeMillis()
            val up = motion(t, t2, MotionEvent.ACTION_UP, x, y)
            inject(up); up.recycle()
        }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        runCatching {
            val steps = (durationMs / 10).coerceIn(2, 100)
            val downTime = SystemClock.uptimeMillis()
            val down = motion(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1)
            inject(down); down.recycle()
            for (i in 1..steps) {
                val f = i.toFloat() / steps
                val x = (x1 + (x2 - x1) * f).toInt()
                val y = (y1 + (y2 - y1) * f).toInt()
                Thread.sleep((durationMs / steps).toLong().coerceAtLeast(1))
                val now = SystemClock.uptimeMillis()
                val move = motion(downTime, now, MotionEvent.ACTION_MOVE, x, y)
                inject(move); move.recycle()
            }
            val end = SystemClock.uptimeMillis()
            val up = motion(downTime, end, MotionEvent.ACTION_UP, x2, y2)
            inject(up); up.recycle()
        }
    }

    override fun exit() {
        destroy()
    }

    override fun destroy() {
        exitProcess(0)
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/service/LoopyService.kt")"
cat > "app/src/main/java/com/loopy/app/service/LoopyService.kt" << 'LOOPY_EOF'
package com.loopy.app.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * 앱 프로세스에서 Shizuku UserService(LoopyUserService)를 바인딩하고 호출을 넘겨주는 싱글톤.
 * 실제 injectInputEvent 는 shell 프로세스(LoopyUserService)에서 일어난다.
 *
 * tap/swipe 은 바인더 호출이라 호출 스레드를 잠깐 붙잡으므로 IO 스레드에서 부를 것.
 * 반환값은 "서비스가 연결돼 있어 호출을 보냈는지" (false 면 아직 미연결/실패).
 */
object LoopyService {

    @Volatile private var svc: ILoopyService? = null
    @Volatile private var binding = false

    fun isReady(): Boolean = svc != null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            svc = if (binder != null && binder.pingBinder()) {
                ILoopyService.Stub.asInterface(binder)
            } else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    private fun args(context: Context) =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, LoopyUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("loopy")
            .debuggable(false)
            .version(1)

    /** Shizuku 권한이 허용된 뒤 호출. 이미 연결됐거나 진행 중이면 무시. */
    fun bind(context: Context) {
        if (svc != null || binding) return
        binding = true
        runCatching { Shizuku.bindUserService(args(context.applicationContext), conn) }
            .onFailure { binding = false }
    }

    fun tap(x: Int, y: Int): Boolean =
        runCatching { svc?.tap(x, y); svc != null }.getOrDefault(false)

    fun doubleTap(x: Int, y: Int, gapMs: Int): Boolean =
        runCatching { svc?.doubleTap(x, y, gapMs); svc != null }.getOrDefault(false)

    fun hold(x: Int, y: Int, durationMs: Int): Boolean =
        runCatching { svc?.hold(x, y, durationMs); svc != null }.getOrDefault(false)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        runCatching { svc?.swipe(x1, y1, x2, y2, durationMs); svc != null }.getOrDefault(false)
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/GestureRecorder.kt")"
cat > "app/src/main/java/com/loopy/app/input/GestureRecorder.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import android.os.SystemClock
import java.util.Collections
import kotlin.math.hypot

/**
 * getevent 로 읽은 터치 포인트(panel 정규화 0~1)를 손가락 down→up 단위로 보고
 * 탭 / 홀드 / 스와이프로 판정해 Action 리스트로 쌓는다.
 *
 * 좌표는 panel 정규화(u,v) 그대로 저장한다. 화면 픽셀 변환(회전 보정)은 재생 쪽에서
 * 현재 방향에 맞춰 수행하므로, 저장은 방향 독립적이다.
 *
 * 판정 기준:
 *  - 이동거리 >= MOVE_THRESH  → 스와이프 (시작→끝, 걸린 시간)
 *  - 그 외 누른 시간 >= HOLD_THRESH_MS → 홀드 (좌표, 시간)
 *  - 그 외 → 탭
 */
class GestureRecorder {

    enum class Type { TAP, HOLD, SWIPE }

    data class Action(
        val delayMs: Long,      // 이전 행동이 끝난 뒤 이 행동 시작까지 대기
        val type: Type,
        val x: Float, val y: Float,     // 시작(panel 0~1)
        val x2: Float, val y2: Float,   // 스와이프 끝
        val durationMs: Long,           // 홀드/스와이프 지속
    )

    val actions: MutableList<Action> = Collections.synchronizedList(mutableListOf())

    /** panel 좌표(u,v)가 무시 대상(예: 컨트롤 바 위)인지. 재생/판정 쪽에서 주입. */
    var shouldIgnore: (Float, Float) -> Boolean = { _, _ -> false }

    private var down = false
    private var downT = 0L
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var lastEndT = 0L

    fun reset() {
        actions.clear()
        down = false
        lastEndT = 0L
    }

    fun onPoint(p: TouchPoint) {
        val now = SystemClock.uptimeMillis()
        when {
            p.down && !down -> { // 손가락 내려감
                down = true
                downT = now
                downX = p.nx; downY = p.ny
                curX = p.nx; curY = p.ny
            }
            p.down -> { // 이동 중
                curX = p.nx; curY = p.ny
            }
            !p.down && down -> { // 손가락 뗌 → 판정
                down = false
                if (shouldIgnore(downX, downY)) {
                    lastEndT = now
                    return
                }
                val dur = now - downT
                val dist = hypot((curX - downX).toDouble(), (curY - downY).toDouble()).toFloat()
                val delay = if (actions.isEmpty()) 0L else (downT - lastEndT).coerceAtLeast(0L)
                val a = when {
                    dist >= MOVE_THRESH ->
                        Action(delay, Type.SWIPE, downX, downY, curX, curY, dur)
                    dur >= HOLD_THRESH_MS ->
                        Action(delay, Type.HOLD, downX, downY, 0f, 0f, dur)
                    else ->
                        Action(delay, Type.TAP, downX, downY, 0f, 0f, 0L)
                }
                actions.add(a)
                lastEndT = now
            }
        }
    }

    companion object {
        // panel 정규화 기준 이동거리 임계(≈ 화면 3%). 넘으면 스와이프.
        const val MOVE_THRESH = 0.03f
        // 이 시간 이상 누르면 홀드로 본다.
        const val HOLD_THRESH_MS = 400L
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/overlay/OverlayService.kt")"
cat > "app/src/main/java/com/loopy/app/overlay/OverlayService.kt" << 'LOOPY_EOF'
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
        status.text = "행동 ${recorder.actions.size}개 · 재생 가능"
    }

    // ── 재생 ──
    private fun play() {
        if (recording) stopRecord()
        val list = recorder.actions.toList()
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
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/MainActivity.kt")"
cat > "app/src/main/java/com/loopy/app/MainActivity.kt" << 'LOOPY_EOF'
package com.loopy.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.overlay.OverlayService
import com.loopy.app.service.LoopyService
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.GlassCard
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.ui.theme.LoopyTheme
import com.loopy.app.ui.theme.MeshGradientBackground
import com.loopy.app.ui.theme.MeshLavender
import com.loopy.app.ui.theme.MeshMint
import com.loopy.app.ui.theme.MeshPeach
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val binderListener = Shizuku.OnBinderReceivedListener { onShizukuChanged?.invoke() }
    private val deadListener = Shizuku.OnBinderDeadListener { onShizukuChanged?.invoke() }
    private var onShizukuChanged: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        setContent {
            LoopyTheme {
                LauncherScreen(registerRefresh = { cb -> onShizukuChanged = cb })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
    }
}

@Composable
private fun LauncherScreen(registerRefresh: ((() -> Unit)) -> Unit) {
    val context = LocalContext.current

    var state by remember { mutableStateOf(ShizukuManager.state()) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var msg by remember { mutableStateOf("오버레이를 켜고 로블록스로 전환한 뒤, 컨트롤 바에서 녹화/재생.") }

    LaunchedEffect(Unit) {
        registerRefresh {
            state = ShizukuManager.state()
            canOverlay = Settings.canDrawOverlays(context)
        }
    }
    LaunchedEffect(state) {
        if (state == ShizukuState.READY) LoopyService.bind(context)
    }

    Box(Modifier.fillMaxSize()) {
        MeshGradientBackground(animate = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Loopy", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("오버레이 탭 테스트", color = TextLo, fontSize = 14.sp)

            // ── Shizuku ──
            GlassCard(Modifier.fillMaxWidth()) {
                Text("1. Shizuku", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    when (state) {
                        ShizukuState.NOT_INSTALLED -> "연결 안 됨 · Shizuku 앱 실행 필요"
                        ShizukuState.NEEDS_PERMISSION -> "설치됨 · 권한 허용 필요"
                        ShizukuState.READY -> "준비 완료"
                    },
                    color = if (state == ShizukuState.READY) Accent else TextLo,
                    fontSize = 13.sp,
                )
                if (state == ShizukuState.NEEDS_PERMISSION) {
                    Spacer(Modifier.height(12.dp))
                    LoopyButton("권한 허용") {
                        ShizukuManager.requestPermission { granted ->
                            state = if (granted) ShizukuState.READY else ShizukuState.NEEDS_PERMISSION
                        }
                    }
                } else if (state == ShizukuState.NOT_INSTALLED) {
                    Spacer(Modifier.height(12.dp))
                    LoopyButton("다시 확인") { state = ShizukuManager.state() }
                }
            }

            // ── 오버레이 권한 ──
            GlassCard(Modifier.fillMaxWidth()) {
                Text("2. 오버레이 권한", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (canOverlay) "허용됨" else "다른 앱 위에 표시 권한이 필요해",
                    color = if (canOverlay) Accent else TextLo,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                if (!canOverlay) {
                    LoopyButton("권한 설정 열기") {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                LoopyButton("권한 상태 새로고침", filled = false) {
                    canOverlay = Settings.canDrawOverlays(context)
                }
            }

            // ── 오버레이 켜기 ──
            GlassCard(Modifier.fillMaxWidth()) {
                Text("3. 오버레이", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(msg, color = TextLo, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                LoopyButton(text = "오버레이 켜기", enabled = canOverlay) {
                    context.startForegroundService(Intent(context, OverlayService::class.java))
                    msg = if (state != ShizukuState.READY)
                        "오버레이는 떴어. 근데 Shizuku가 준비 안 돼서 탭은 안 먹을 거야. 위에서 Shizuku부터 켜줘."
                    else
                        "켜졌어! 로블록스로 전환 → '● 녹화'로 플레이 기록 → '▶ 재생'."
                }
                Spacer(Modifier.height(8.dp))
                LoopyButton("오버레이 끄기", filled = false) {
                    context.stopService(Intent(context, OverlayService::class.java))
                    msg = "오버레이를 껐어."
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 파스텔 그라데이션 알약 버튼. filled=false 는 흰 카드 + 얇은 테두리(보조). */
@Composable
private fun LoopyButton(
    text: String,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .fillMaxWidth()
        .height(50.dp)
        .clip(shape)
        .alpha(if (enabled) 1f else 0.45f)
    val styled = if (filled) {
        base.background(Brush.horizontalGradient(listOf(MeshPeach, MeshLavender, MeshMint)))
    } else {
        base.background(LoopyCard).border(1.dp, CardStroke, shape)
    }
    val clickMod = if (enabled) styled.clickable { onClick() } else styled
    Box(clickMod, contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (filled) TextHi else Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
LOOPY_EOF

echo "반영 완료."
git add -A
git commit -m "B-2: getevent 제스처 녹화(탭/홀드/스와이프) → injectInputEvent 재생"
git push
echo "푸시 완료!"

