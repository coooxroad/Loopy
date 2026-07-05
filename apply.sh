#!/data/data/com.termux/files/usr/bin/bash
# Loopy B-1: injectInputEvent (Shizuku UserService) 탭
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

mkdir -p "$(dirname "app/build.gradle.kts")"
cat > "app/build.gradle.kts" << 'LOOPY_EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.loopy.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.loopy.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-m0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        aidl = true
    }

    // Shizuku.newProcess 등이 @RestrictTo 로 표시돼 있어 lint 가 막지 않도록.
    // (assembleDebug 는 lint 를 안 돌리지만 안전장치로 둔다.)
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Shizuku: shell/root 권한으로 getevent 실행
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
LOOPY_EOF

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
class LoopyUserService : ILoopyService.Stub {

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

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        runCatching { svc?.swipe(x1, y1, x2, y2, durationMs); svc != null }.getOrDefault(false)
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
    var msg by remember { mutableStateOf("오버레이를 켠 뒤, 로블록스로 전환해서 '여기 탭'을 눌러봐.") }

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
                        "켜졌어! 로블록스로 전환 → 조준점을 놓을 자리로 드래그 → '여기 탭'."
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

echo "파일 6개 반영."
git add -A
git commit -m "B-1: Shizuku UserService + injectInputEvent 탭 주입, 좌표 오프셋 수정"
git push
echo "푸시 완료!"

