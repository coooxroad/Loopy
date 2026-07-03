package com.loopy.app

import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.input.InputInjector
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState
import com.loopy.app.ui.theme.GlassCard
import com.loopy.app.ui.theme.LoopyTheme
import com.loopy.app.ui.theme.LoopyViolet
import com.loopy.app.ui.theme.MeshGradientBackground
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                M1aScreen(registerRefresh = { cb -> onShizukuChanged = cb })
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
private fun M1aScreen(registerRefresh: ((() -> Unit)) -> Unit) {
    val scope = rememberCoroutineScope()
    val injector = remember { InputInjector(scope) }

    var state by remember { mutableStateOf(ShizukuManager.state()) }
    var tapCount by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var targetCenter by remember { mutableStateOf(Offset.Zero) }
    var lastMsg by remember { mutableStateOf("발사 버튼을 누르면 3초 뒤 중앙을 자동으로 탭해.") }

    LaunchedEffect(Unit) {
        registerRefresh { state = ShizukuManager.state() }
    }

    Box(Modifier.fillMaxSize()) {
        MeshGradientBackground(animate = countdown == null)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Loopy", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("M1a · 재생(주입) 엔진 검증", color = TextLo, fontSize = 14.sp)

            // ── Shizuku 상태 ──
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Shizuku", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    when (state) {
                        ShizukuState.NOT_INSTALLED -> "연결 안 됨 · Shizuku 앱을 실행해줘"
                        ShizukuState.NEEDS_PERMISSION -> "설치됨 · 권한 허용 필요"
                        ShizukuState.READY -> "준비 완료"
                    },
                    color = if (state == ShizukuState.READY) LoopyViolet else TextLo,
                    fontSize = 13.sp,
                )
                if (state == ShizukuState.NEEDS_PERMISSION) {
                    Spacer(Modifier.height(14.dp))
                    LoopyButton("권한 허용") {
                        ShizukuManager.requestPermission { granted ->
                            state = if (granted) ShizukuState.READY else ShizukuState.NEEDS_PERMISSION
                        }
                    }
                } else if (state == ShizukuState.NOT_INSTALLED) {
                    Spacer(Modifier.height(14.dp))
                    LoopyButton("다시 확인") { state = ShizukuManager.state() }
                }
            }

            // ── 주입 테스트 ──
            GlassCard(Modifier.fillMaxWidth()) {
                Text("주입 테스트", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(lastMsg, color = TextLo, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))

                LoopyButton(
                    text = if (countdown != null) "발사까지 ${countdown}..." else "가상 탭 발사 (3초 뒤)",
                    enabled = state == ShizukuState.READY && countdown == null,
                ) {
                    scope.launch {
                        val c = targetCenter
                        if (c == Offset.Zero) {
                            lastMsg = "타깃 위치를 아직 못 잡았어. 잠깐 뒤 다시 눌러봐."
                            return@launch
                        }
                        for (i in 3 downTo 1) { countdown = i; delay(1000) }
                        countdown = null
                        val x = c.x.toInt()
                        val y = c.y.toInt()
                        lastMsg = "주입: input tap $x $y … 상자 숫자가 오르면 성공!"
                        injector.tap(x, y)
                    }
                }
            }

            // ── 타깃 상자 (직접 누르지 말 것) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                    .onGloballyPositioned { targetCenter = it.boundsInWindow().center }
                    .clickable {
                        tapCount += 1
                        lastMsg = "탭 감지됨! (누적 $tapCount)"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$tapCount", color = LoopyViolet, fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("탭 카운트", color = TextLo, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("이 상자를 직접 누르지 말고,\n위 발사 버튼만 눌러봐.", color = TextLo, fontSize = 12.sp)
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
