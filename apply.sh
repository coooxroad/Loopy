#!/data/data/com.termux/files/usr/bin/bash
# Loopy 오버레이 탭 테스트
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행 (cd ~/Loopy)"; exit 1; fi

mkdir -p "$(dirname "app/src/main/AndroidManifest.xml")"
cat > "app/src/main/AndroidManifest.xml" << 'LOOPY_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- 화면 끄기 오버레이용 (M2에서 사용) -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <!-- 포그라운드 서비스 (재생 유지용, M2) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Loopy"
        tools:targetApi="31">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Loopy">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Shizuku 권한 위임 프로바이더 -->
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:multiprocess="false"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

        <!-- 화면 위 오버레이 컨트롤 (탭 테스트 / 나중에 매크로 컨트롤) -->
        <service
            android:name=".overlay.OverlayService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="loopy_overlay_control" />
        </service>
    </application>
</manifest>
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.overlay.OverlayService
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState
import com.loopy.app.ui.theme.GlassCard
import com.loopy.app.ui.theme.LoopyTheme
import com.loopy.app.ui.theme.LoopyViolet
import com.loopy.app.ui.theme.MeshGradientBackground
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
                    color = if (state == ShizukuState.READY) LoopyViolet else TextLo,
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
                    color = if (canOverlay) LoopyViolet else TextLo,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                if (!canOverlay) {
                    LoopyButton("권한 설정 열기") {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        context.startActivity(intent)
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
                LoopyButton(
                    text = "오버레이 켜기",
                    enabled = canOverlay,
                ) {
                    context.startForegroundService(Intent(context, OverlayService::class.java))
                    msg = if (state != ShizukuState.READY)
                        "오버레이는 떴어. 근데 Shizuku가 준비 안 돼서 탭은 안 먹을 거야. 위에서 Shizuku부터 켜줘."
                    else
                        "켜졌어! 이제 로블록스로 전환 → 조준점을 타워 놓을 자리로 드래그 → '여기 탭'."
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

@Composable
private fun LoopyButton(
    text: String,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) LoopyViolet else Color(0x1AFFFFFF),
            contentColor = if (filled) Color.White else TextHi,
            disabledContainerColor = Color(0x0DFFFFFF),
            disabledContentColor = TextLo,
        ),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
LOOPY_EOF

echo "파일 3개 반영."
git add -A
git commit -m "오버레이 탭 테스트 (드래그 조준점 + input tap)"
git push
echo "푸시 완료!"

