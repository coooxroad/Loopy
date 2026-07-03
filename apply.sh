#!/data/data/com.termux/files/usr/bin/bash
# Loopy M1a 적용 스크립트 — Loopy 폴더 안에서 실행
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행해줘 (cd ~/Loopy)"; exit 1; fi

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
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/InputInjector.kt")"
cat > "app/src/main/java/com/loopy/app/input/InputInjector.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import com.loopy.app.shizuku.Shell

/**
 * M1a 재생 엔진의 최소 버전. `input` 명령으로 터치를 주입한다.
 *
 * 참고: `input tap/swipe` 는 내부적으로 InputManager.injectInputEvent 를 쓴다.
 * 즉 여기서 탭이 먹히면, M1b 에서 injectInputEvent 를 직접 부르는 정밀 재생도
 * 같은 경로라 동작한다는 신호다. 다만 `input` 은 매 호출이 프로세스를 포크해서
 * 느리고 정밀 타이밍/멀티터치가 안 되므로, 실제 매크로 재생(M1b)에서는
 * UserService + injectInputEvent 로 교체할 예정. 지금은 "주입이 되는가"만 확인.
 */
class InputInjector {

    /** 화면 픽셀 좌표 (x, y) 를 한 번 탭. */
    fun tap(x: Int, y: Int) {
        Shell.exec("input tap $x $y")
    }

    /** (x1,y1) → (x2,y2) 로 durationMs 동안 스와이프. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        Shell.exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/GeteventReader.kt")"
cat > "app/src/main/java/com/loopy/app/input/GeteventReader.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/** 정규화된 터치 한 점 (0.0~1.0). raw 픽셀도 참고용으로 함께 보관. */
data class TouchPoint(
    val nx: Float,          // 0.0 ~ 1.0
    val ny: Float,          // 0.0 ~ 1.0
    val rawX: Int,
    val rawY: Int,
    val down: Boolean,      // 손가락이 닿아있는 프레임인지
)

/** getevent -pl 로 찾아낸 터치 가능 디바이스 정보. */
data class TouchDevice(
    val path: String,       // /dev/input/eventX
    val name: String,
    val maxX: Int,
    val maxY: Int,
)

/**
 * M0의 핵심. Shizuku 셸로 getevent 를 돌려 물리 터치를 실시간 파싱한다.
 *
 * 기기마다 터치스크린 노드(eventX)와 이름이 달라서 하나만 찍어 고르면 틀리기 쉽다.
 * (예: sec_touchpad vs sec_touchscreen) 그래서 M0에서는 ABS_MT 를 가진 디바이스를
 * "전부" 동시에 스트리밍하고, 각 좌표에 디바이스를 태그해 보여준다 → 화면을 만지면
 * 어느 노드가 진짜 터치스크린인지 즉시 드러난다.
 *
 * 재생 쪽(injectInputEvent)과 달리 getevent 는 /dev/input 하드웨어 레이어를 직접 읽으므로,
 * 우리가 주입한 이벤트는 여기 안 잡히고 물리 터치만 잡힌다.
 */
class GeteventReader {

    private val jobs = mutableListOf<Job>()

    /**
     * getevent -pl 결과에서 ABS_MT_POSITION_X/Y 를 가진 디바이스를 전부 찾는다.
     * 이름에 "touchscreen" 이 들어간 걸 앞으로 정렬(참고용)하되, 실제 판별은 스트리밍으로.
     */
    fun probe(): List<TouchDevice> {
        val out = runBlockingShell("getevent -pl") ?: return emptyList()

        var curPath: String? = null
        var curName = ""
        var maxX = -1
        var maxY = -1
        val found = mutableListOf<TouchDevice>()

        fun flush() {
            val p = curPath
            if (p != null && maxX > 0 && maxY > 0) {
                found += TouchDevice(p, curName, maxX, maxY)
            }
        }

        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("add device") -> {
                    flush()
                    curPath = line.substringAfter(": ", "").trim().ifEmpty { null }
                    curName = ""; maxX = -1; maxY = -1
                }
                line.startsWith("name:") -> {
                    curName = line.substringAfter("name:").trim().trim('"')
                }
                line.contains("ABS_MT_POSITION_X") -> {
                    maxX = extractMax(line).coerceAtLeast(maxX)
                }
                line.contains("ABS_MT_POSITION_Y") -> {
                    maxY = extractMax(line).coerceAtLeast(maxY)
                }
            }
        }
        flush()

        return found.sortedByDescending { it.name.contains("touchscreen", ignoreCase = true) }
    }

    /** "... max 4095, ..." 형태에서 max 뒤 숫자를 뽑는다. */
    private fun extractMax(line: String): Int {
        val i = line.indexOf("max")
        if (i < 0) return -1
        val rest = line.substring(i + 3)
        val num = rest.dropWhile { !it.isDigit() && it != '-' }
            .takeWhile { it.isDigit() || it == '-' }
        return num.toIntOrNull() ?: -1
    }

    /**
     * 넘어온 디바이스들을 "전부 동시에" 스트리밍. onPoint 는 어느 디바이스에서 왔는지와 함께
     * SYN_REPORT 마다(=한 프레임) 호출된다. stop() 하면 전부 죽인다.
     */
    fun stream(
        scope: CoroutineScope,
        devices: List<TouchDevice>,
        onPoint: (TouchDevice, TouchPoint) -> Unit,
    ) {
        stop()
        for (dev in devices) {
            jobs += scope.launch(Dispatchers.IO) { streamOne(dev, onPoint) }
        }
    }

    private fun streamOne(dev: TouchDevice, onPoint: (TouchDevice, TouchPoint) -> Unit) {
        val proc = try {
            Shell.newProcess(arrayOf("sh", "-c", "getevent -lt ${dev.path}"))
        } catch (t: Throwable) {
            return
        }
        var curX = -1
        var curY = -1
        var down = false
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    val ev = parseLine(line) ?: continue
                    when (ev.code) {
                        "ABS_MT_POSITION_X" -> curX = ev.value
                        "ABS_MT_POSITION_Y" -> curY = ev.value
                        "ABS_MT_TRACKING_ID" ->
                            down = ev.value != 0xffffffff.toInt() && ev.value != -1
                        "BTN_TOUCH" -> down = ev.value == 1
                        "SYN_REPORT" -> {
                            if (curX in 0..dev.maxX && curY in 0..dev.maxY) {
                                onPoint(
                                    dev,
                                    TouchPoint(
                                        nx = curX.toFloat() / dev.maxX,
                                        ny = curY.toFloat() / dev.maxY,
                                        rawX = curX,
                                        rawY = curY,
                                        down = down,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 스트림 종료/취소 시 조용히 빠져나옴
        } finally {
            runCatching { proc.destroy() }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private data class Ev(val type: String, val code: String, val value: Int)

    /**
     * `[   86185.123456] EV_ABS       ABS_MT_POSITION_X    000007a1`
     * 형태를 파싱. 값은 16진수. 디바이스 경로를 인자로 준 스트림이라 앞 접두어는 없다.
     */
    private fun parseLine(line: String): Ev? {
        val body = if (line.startsWith("[")) line.substringAfter("]").trim() else line.trim()
        val toks = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size < 3) return null
        val type = toks[0]
        if (!type.startsWith("EV_")) return null
        val code = toks[1]
        val value = toks[2].toLongOrNull(16)?.toInt() ?: return null
        return Ev(type, code, value)
    }

    /** 짧게 끝나는 셸 명령을 동기 실행하고 stdout 을 통째로 반환. */
    private fun runBlockingShell(cmd: String): String? {
        return try {
            val proc = Shell.newProcess(arrayOf("sh", "-c", cmd))
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text
        } catch (t: Throwable) {
            null
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
    val injector = remember { InputInjector() }

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

echo "파일 4개 반영 완료."
git add -A
git commit -m "M1a: input tap 주입 엔진 + Shell 공용화"
git push
echo "푸시 완료! 깃허브 Actions 확인해봐."



