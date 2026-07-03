#!/data/data/com.termux/files/usr/bin/bash
# Loopy M1b-1 (녹화 → sendevent 재생) — Loopy 폴더 안에서 실행
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행 (cd ~/Loopy)"; exit 1; fi

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/Recorder.kt")"
cat > "app/src/main/java/com/loopy/app/input/Recorder.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * 기기가 실제로 뿜은 raw 입력 이벤트 한 줄.
 * type/code/value 는 리눅스 input_event 의 원시 숫자값 그대로(추측/변환 없음).
 * value 는 0xffffffff(=트래킹ID 해제) 같은 값을 담아야 해서 Long 으로 둔다.
 */
data class RawEvent(
    val tMicros: Long,   // getevent 타임스탬프(마이크로초, 절대값)
    val type: Int,
    val code: Int,
    val value: Long,
)

/**
 * 터치스크린의 raw getevent 스트림을 그대로 캡처한다.
 * 파싱/정규화 없이 원본 숫자를 저장하므로, 이걸 sendevent 로 되쏘면
 * 기기 자신의 프로토콜이라 100% 인식된다(M1b-1 검증의 핵심).
 *
 * getevent -t (숫자 hex + 타임스탬프) 사용. M0 에서 -lt 가 동작했으므로 -t 도 지원됨.
 */
class Recorder {

    private var job: Job? = null
    val events: MutableList<RawEvent> = Collections.synchronizedList(mutableListOf())

    fun start(scope: CoroutineScope, dev: TouchDevice) {
        stop()
        events.clear()
        job = scope.launch(Dispatchers.IO) {
            val proc = try {
                Shell.newProcess(arrayOf("sh", "-c", "getevent -t ${dev.path}"))
            } catch (t: Throwable) {
                return@launch
            }
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    parse(line)?.let { events.add(it) }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { proc.destroy() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** `[   86185.123456] 0003 0035 000007a1` → RawEvent. 마지막 3토큰을 hex 로 읽는다. */
    private fun parse(line: String): RawEvent? {
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open < 0 || close < 0 || close <= open) return null
        val tMicros = try {
            (line.substring(open + 1, close).trim().toDouble() * 1_000_000).toLong()
        } catch (_: Throwable) {
            return null
        }
        val toks = line.substring(close + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size < 3) return null
        val n = toks.size
        return try {
            RawEvent(
                tMicros = tMicros,
                type = toks[n - 3].toInt(16),
                code = toks[n - 2].toInt(16),
                value = toks[n - 1].toLong(16),
            )
        } catch (_: Throwable) {
            null
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
import java.util.Locale

/**
 * 캡처한 raw 이벤트를 sendevent 로 되쏜다.
 *
 * 성능/타이밍의 핵심: 이벤트마다 sendevent 를 "따로" 실행하면(프로세스 포크) 느리고
 * 타이밍이 뭉개진다. 그래서 전체 시퀀스를 sleep 이 섞인 "하나의 셸 스크립트"로 만들어
 * 한 프로세스에서 실행한다. (M1b-1 은 짧은 시퀀스라 이걸로 충분. 긴 매크로의
 * 추가 최적화는 나중에.)
 *
 * value 변환: getevent 는 0xffffffff 를 그대로 준다(=트래킹ID 해제, 의미상 -1).
 * sendevent 에는 부호 있는 정수로 넣어야 하므로 32비트 부호 해석(Long→Int)한다.
 * 예: 0xffffffff → -1, 0x7a1 → 1953.
 */
class Player(
    private val scope: CoroutineScope,
) {

    fun play(dev: TouchDevice, events: List<RawEvent>, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            if (events.isNotEmpty()) {
                val sb = StringBuilder()
                var prev = events.first().tMicros
                for (e in events) {
                    val dt = e.tMicros - prev
                    if (dt >= 5_000) { // 5ms 이상 간격만 sleep 으로 재현
                        sb.append("sleep ")
                            .append(String.format(Locale.US, "%.3f", dt / 1_000_000.0))
                            .append('\n')
                    }
                    sb.append("sendevent ").append(dev.path).append(' ')
                        .append(e.type).append(' ')
                        .append(e.code).append(' ')
                        .append(e.value.toInt()) // 32비트 부호 해석
                        .append('\n')
                    prev = e.tMicros
                }
                Shell.exec(sb.toString())
            }
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
                                recordedCount = recorder.events.size
                                phase = null
                                busy = false
                                lastMsg = "이벤트 $recordedCount개 녹화됨. '재생'을 눌러봐."
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
                                player.play(dev, evs)
                                delay(durMs + 800)
                                phase = null
                                busy = false
                                lastMsg = "재생 끝. 상자 숫자가 저절로 늘었으면 성공! 🎯"
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

echo "파일 3개 반영 완료."
git add -A
git commit -m "M1b-1: raw getevent 녹화 → sendevent 재생"
git push
echo "푸시 완료! Actions 확인."

