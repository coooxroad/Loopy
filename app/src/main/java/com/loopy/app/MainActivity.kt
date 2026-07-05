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
