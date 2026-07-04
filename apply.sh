#!/data/data/com.termux/files/usr/bin/bash
# Loopy M1b-1 재생 진단 추가
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행 (cd ~/Loopy)"; exit 1; fi

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/shizuku/Shell.kt")"
cat > "app/src/main/java/com/loopy/app/shizuku/Shell.kt" << 'LOOPY_EOF'
package com.loopy.app.shizuku

import rikka.shizuku.Shizuku

/**
 * Shizuku 셸 실행을 한 곳으로 모은 진입점.
 *
 * Shizuku.newProcess 는 이 API 버전에서 private 이라 리플렉션으로 호출한다.
 * (rikka.shizuku.* 는 안드로이드 프레임워크 클래스가 아니라 hidden-API 제약이 없고,
 *  debug 빌드라 난독화도 없어 안전하다. 반환형 ShizukuRemoteProcess 는
 *  java.lang.Process 를 상속하므로 Process 로 받아 쓴다.)
 */
object Shell {

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    /** raw 프로세스 생성. 스트리밍(getevent)처럼 살아있는 프로세스가 필요할 때 사용. */
    fun newProcess(cmd: Array<String>): Process =
        newProcessMethod.invoke(null, cmd, null, null) as Process

    private fun sh(cmd: String): Process = newProcess(arrayOf("sh", "-c", cmd))

    /** 명령을 실행하고 stdout 을 통째로 반환(동기). 실패 시 null. */
    fun run(cmd: String): String? = try {
        val p = sh(cmd)
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    } catch (t: Throwable) {
        null
    }

    /** 출력이 필요 없는 명령을 실행하고 끝날 때까지 대기(동기). */
    fun exec(cmd: String) {
        try {
            sh(cmd).waitFor()
        } catch (_: Throwable) {
        }
    }

    /** 진단용: 실행 후 exit 코드 + stderr + stdout 을 요약 문자열로 반환. */
    fun execDiag(cmd: String): String {
        return try {
            val p = sh(cmd)
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            buildString {
                append("exit=").append(code)
                if (err.isNotBlank()) append("\nERR: ").append(err.take(300))
                if (out.isNotBlank()) append("\nOUT: ").append(out.take(200))
            }
        } catch (t: Throwable) {
            "예외: ${t.message}"
        }
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/Player.kt")"
cat > "app/src/main/java/com/loopy/app/input/Player.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 캡처한 raw 이벤트를 sendevent 로 되쏜다.
 *
 * 전체 시퀀스를 sleep 이 섞인 "하나의 셸 스크립트"로 만들어 한 프로세스에서 실행한다.
 * 진단 모드: execDiag 로 sendevent 의 exit/stderr 를 받아 onResult 로 돌려준다.
 *
 * value 변환: getevent 는 0xffffffff(=트래킹ID 해제)를 그대로 준다.
 * sendevent 엔 부호 있는 32비트로 넣어야 하므로 Long→Int 로 부호 해석한다.
 * 예: 0xffffffff → -1, 0x7a1 → 1953.
 */
class Player(
    private val scope: CoroutineScope,
) {

    fun play(dev: TouchDevice, events: List<RawEvent>, onResult: (String) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            if (events.isEmpty()) {
                withContext(Dispatchers.Main) { onResult("이벤트 없음") }
                return@launch
            }
            val sb = StringBuilder()
            var prev = events.first().tMicros
            for (e in events) {
                val dt = e.tMicros - prev
                if (dt >= 5_000) {
                    sb.append("sleep ")
                        .append(String.format(Locale.US, "%.3f", dt / 1_000_000.0))
                        .append('\n')
                }
                sb.append("sendevent ").append(dev.path).append(' ')
                    .append(e.type).append(' ')
                    .append(e.code).append(' ')
                    .append(e.value.toInt())
                    .append('\n')
                prev = e.tMicros
            }
            val diag = Shell.execDiag(sb.toString())
            withContext(Dispatchers.Main) { onResult(diag) }
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.Player
import com.loopy.app.input.Recorder
import com.loopy.app.input.TouchDevice
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
                M1bScreen(registerRefresh = { cb -> onShizukuChanged = cb })
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
private fun M1bScreen(registerRefresh: ((() -> Unit)) -> Unit) {
    val scope = rememberCoroutineScope()
    val reader = remember { GeteventReader() }
    val recorder = remember { Recorder() }
    val player = remember { Player(scope) }

    var state by remember { mutableStateOf(ShizukuManager.state()) }
    var device by remember { mutableStateOf<TouchDevice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<String?>(null) }
    var recordedCount by remember { mutableIntStateOf(0) }
    var tapCount by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf("") }
    var diag by remember { mutableStateOf("") }
    var lastMsg by remember { mutableStateOf("녹화 → (상자 탭) → 재생 순서로 확인해보자.") }

    LaunchedEffect(Unit) { registerRefresh { state = ShizukuManager.state() } }

    Box(Modifier.fillMaxSize()) {
        MeshGradientBackground(animate = phase == null)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Loopy", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("M1b-1 · 녹화 → sendevent 재생", color = TextLo, fontSize = 14.sp)

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

            // ── 녹화 / 재생 ──
            GlassCard(Modifier.fillMaxWidth()) {
                Text("녹화 · 재생", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(phase ?: lastMsg, color = if (phase != null) LoopyViolet else TextLo, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text("녹화된 이벤트: $recordedCount 개", color = TextLo, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("첫 이벤트 미리보기:", color = TextLo, fontSize = 11.sp)
                    Text(preview, color = TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (diag.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("재생 진단:", color = TextLo, fontSize = 11.sp)
                    Text(diag, color = LoopyViolet, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        LoopyButton(
                            text = "녹화 (3초)",
                            enabled = state == ShizukuState.READY && !busy,
                        ) {
                            scope.launch {
                                val devs = reader.probe()
                                val dev = devs.firstOrNull { it.name.contains("touchscreen", true) }
                                    ?: devs.firstOrNull()
                                if (dev == null) {
                                    lastMsg = "터치스크린을 못 찾음 (getevent -pl 실패?)"
                                    return@launch
                                }
                                device = dev
                                busy = true
                                phase = "준비… 2"; delay(1000)
                                phase = "준비… 1"; delay(1000)
                                recorder.start(scope, dev)
                                for (i in 3 downTo 1) { phase = "● 녹화중 $i — 아래 상자를 탭!"; delay(1000) }
                                recorder.stop()
                                val evs = recorder.events.toList()
                                recordedCount = evs.size
                                preview = evs.take(8).joinToString("\n") {
                                    "type=${it.type}  code=${it.code}  val=${it.value}"
                                }
                                diag = ""
                                phase = null
                                busy = false
                                lastMsg = "이벤트 ${recordedCount}개 녹화됨. '재생'을 눌러봐."
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        LoopyButton(
                            text = "재생 (2초)",
                            filled = false,
                            enabled = state == ShizukuState.READY && !busy && recordedCount > 0,
                        ) {
                            scope.launch {
                                val dev = device ?: return@launch
                                val evs = recorder.events.toList()
                                if (evs.isEmpty()) { lastMsg = "먼저 녹화해줘"; return@launch }
                                busy = true
                                for (i in 2 downTo 1) { phase = "재생까지 $i"; delay(1000) }
                                phase = "▶ 재생중…"
                                val durMs = ((evs.last().tMicros - evs.first().tMicros) / 1000).coerceAtLeast(0)
                                player.play(dev, evs) { result -> diag = result }
                                delay(durMs + 800)
                                phase = null
                                busy = false
                                lastMsg = "재생 끝. 상자 숫자가 올랐으면 성공. 안 올랐으면 아래 진단을 봐."
                            }
                        }
                    }
                }
            }

            // ── 타깃 상자 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                    .clickable {
                        tapCount += 1
                        lastMsg = "탭 감지 (누적 $tapCount)"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$tapCount", color = LoopyViolet, fontSize = 60.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("탭 카운트", color = TextLo, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("녹화중일 때 여기를 몇 번 탭해.\n재생 때 숫자가 저절로 오르면 성공.", color = TextLo, fontSize = 12.sp)
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
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
LOOPY_EOF

echo "파일 3개 반영."
git add -A
git commit -m "M1b-1 진단: sendevent 결과/이벤트 미리보기 표시"
git push
echo "푸시 완료!"

