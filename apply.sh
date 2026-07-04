#!/data/data/com.termux/files/usr/bin/bash
# Loopy: 탭 수정 + 파스텔 UI + 죽은코드 정리
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행 (cd ~/Loopy)"; exit 1; fi

rm -f "app/src/main/java/com/loopy/app/input/Recorder.kt"
rm -f "app/src/main/java/com/loopy/app/input/Player.kt"

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/ui/theme/Theme.kt")"
cat > "app/src/main/java/com/loopy/app/ui/theme/Theme.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Loopy 팔레트 — 오프화이트 베이스 + 파스텔 메쉬 (Soft Flow)
val LoopyBg = Color(0xFFF8F9FA)      // 소프트 스노우 배경
val LoopyCard = Color(0xFFFFFFFF)    // 퓨어 화이트 카드
val TextHi = Color(0xFF2B2D42)       // 딥 차콜 (주요 텍스트)
val TextLo = Color(0xFF8A8DA0)       // 뮤트 그레이 (보조 텍스트)

val Accent = Color(0xFF6C7BFF)       // 페리윙클 (강조/버튼 텍스트)
val LoopyViolet = Accent             // 기존 참조 호환용 별칭

// 파스텔 메쉬 3색
val MeshPeach = Color(0xFFFFB8B1)    // 피치 오로라 (녹화/액션)
val MeshLavender = Color(0xFFCDDAFD) // 소프트 라벤더 (연결)
val MeshMint = Color(0xFFB5E2FA)     // 민트 브리즈 (재생/루프)

val CardStroke = Color(0x142B2D42)   // 차콜 8% 테두리

private val LoopyColors = lightColorScheme(
    primary = Accent,
    secondary = MeshMint,
    background = LoopyBg,
    surface = LoopyCard,
    onPrimary = Color.White,
    onBackground = TextHi,
    onSurface = TextHi,
)

@Composable
fun LoopyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LoopyColors,
        typography = Typography(),
        content = content,
    )
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/ui/theme/MeshGradient.kt")"
cat > "app/src/main/java/com/loopy/app/ui/theme/MeshGradient.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 오프화이트 배경 위에 파스텔(피치/라벤더/민트) 블롭을 크고 흐리게 겹쳐 아주 느리게
 * 움직여 mesh 느낌을 낸다. 밝은 배경이라 alpha 를 살짝 높여 은은하게 보이게 한다.
 * 재생 중에는 animate=false 로 꺼서 GPU/배터리를 아낀다.
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "mesh")
    val phase = if (animate) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(30_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        ).value
    } else 0f

    Canvas(modifier = modifier.fillMaxSize().background(LoopyBg)) {
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.85f

        blob(MeshPeach, w * (0.22f + 0.10f * cos(phase)), h * (0.18f + 0.08f * sin(phase)), r * 0.9f)
        blob(MeshMint, w * (0.82f + 0.10f * sin(phase * 0.9f)), h * (0.26f + 0.10f * cos(phase * 1.1f)), r * 0.85f)
        blob(MeshLavender, w * (0.55f + 0.12f * cos(phase * 1.3f)), h * (0.88f + 0.06f * sin(phase)), r)
        blob(MeshPeach, w * (0.12f + 0.08f * sin(phase * 0.7f)), h * (0.82f + 0.08f * cos(phase * 0.8f)), r * 0.6f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.blob(
    color: Color, cx: Float, cy: Float, radius: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.65f), color.copy(alpha = 0f)),
            center = Offset(cx, cy),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, cy),
    )
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/ui/theme/Glass.kt")"
cat > "app/src/main/java/com/loopy/app/ui/theme/Glass.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 카드. 파스텔 라이트 테마에선 퓨어 화이트 + 부드러운 그림자(elevation)로 '공중에 뜬'
 * 클린 플랫 느낌을 낸다. 아주 얇은 차콜 테두리로 경계를 살짝 잡아준다.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier,
        color = LoopyCard,
        shape = shape,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, CardStroke),
    ) {
        Column(
            modifier = Modifier.padding(padding),
            content = content,
        )
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
import com.loopy.app.shizuku.Shell
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

    private fun tapAtCrosshair() {
        val cx = crosshairParams.x + crosshair.width / 2
        val cy = crosshairParams.y + crosshair.height / 2
        coordLabel.text = "탭 주입: ($cx, $cy)…"
        setCrosshairTouchable(false) // 조준점이 탭을 가로채지 않게
        scope.launch {
            delay(60)
            val diag = withContext(Dispatchers.IO) { Shell.execDiag("input tap $cx $cy") }
            setCrosshairTouchable(true)
            coordLabel.text = "($cx,$cy) → ${diag.take(110)}"
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

echo "파일 5개 반영, 2개 삭제."
git add -A
git commit -m "탭 수정(조준점 통과)+진단, 파스텔 UI, sendevent 죽은코드 제거"
git push
echo "푸시 완료!"

