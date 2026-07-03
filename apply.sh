#!/data/data/com.termux/files/usr/bin/bash
# Loopy M1a 수정(주입 IO 스레드) — Loopy 폴더 안에서 실행
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행해줘 (cd ~/Loopy)"; exit 1; fi

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/InputInjector.kt")"
cat > "app/src/main/java/com/loopy/app/input/InputInjector.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * M1a 재생 엔진의 최소 버전. `input` 명령으로 터치를 주입한다.
 *
 * 중요: Shell.exec 안의 waitFor() 는 프로세스가 끝날 때까지 스레드를 붙잡는다.
 * 이걸 메인 스레드에서 부르면 UI 가 얼어 "응답하지 않음"으로 앱이 죽는다.
 * 그래서 모든 주입은 IO 디스패처에서 돌린다.
 *
 * 참고: `input tap/swipe` 는 내부적으로 InputManager.injectInputEvent 를 쓴다.
 * 여기서 탭이 먹히면, M1b 에서 injectInputEvent 를 직접 부르는 정밀 재생도
 * 같은 경로라 동작한다는 신호다. 다만 `input` 은 매 호출이 프로세스를 포크해서
 * 느리고 정밀 타이밍/멀티터치가 안 되므로, 실제 매크로 재생(M1b)에서는
 * UserService + injectInputEvent 로 교체할 예정. 지금은 "주입이 되는가"만 확인.
 */
class InputInjector(
    private val scope: CoroutineScope,
) {

    /** 화면 픽셀 좌표 (x, y) 를 한 번 탭. 백그라운드(IO)에서 실행. */
    fun tap(x: Int, y: Int, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            Shell.exec("input tap $x $y")
            onDone()
        }
    }

    /** (x1,y1) → (x2,y2) 로 durationMs 동안 스와이프. 백그라운드(IO)에서 실행. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            Shell.exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
            onDone()
        }
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/MainActivity.kt")"
cat > "app/src/main/java/com/loopy/app/MainActivity.kt" << 'LOOPY_EOF'
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
LOOPY_EOF

echo "파일 2개 반영 완료."
git add -A
git commit -m "M1a fix: 주입을 IO 스레드에서 실행 (ANR 방지)"
git push
echo "푸시 완료! Actions 확인."

