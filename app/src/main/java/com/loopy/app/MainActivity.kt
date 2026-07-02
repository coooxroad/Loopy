package com.loopy.app

import android.os.Bundle
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.TouchDevice
import com.loopy.app.input.TouchPoint
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState
import com.loopy.app.ui.theme.GlassCard
import com.loopy.app.ui.theme.LoopyTheme
import com.loopy.app.ui.theme.LoopyViolet
import com.loopy.app.ui.theme.MeshGradientBackground
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo
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
                M0Screen(
                    registerRefresh = { cb -> onShizukuChanged = cb },
                )
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
private fun M0Screen(registerRefresh: ((() -> Unit)) -> Unit) {
    val scope = rememberCoroutineScope()
    val reader = remember { GeteventReader() }

    var state by remember { mutableStateOf(ShizukuManager.state()) }
    var streaming by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<TouchPoint?>(null) }
    var lastDev by remember { mutableStateOf<TouchDevice?>(null) }
    val log = remember { mutableStateListOf<String>() }

    // Shizuku 바인더 상태 변화 시 화면 갱신
    LaunchedEffect(Unit) {
        registerRefresh { state = ShizukuManager.state() }
    }

    Box(Modifier.fillMaxSize()) {
        MeshGradientBackground(animate = !streaming)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Loopy", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("M0 · 터치 감지 검증", color = TextLo, fontSize = 14.sp)

            // ── 상태 카드 ──
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
                Spacer(Modifier.height(14.dp))

                when (state) {
                    ShizukuState.NEEDS_PERMISSION -> LoopyButton("권한 허용") {
                        ShizukuManager.requestPermission { granted ->
                            state = if (granted) ShizukuState.READY else ShizukuState.NEEDS_PERMISSION
                        }
                    }
                    ShizukuState.READY -> {
                        if (!streaming) {
                            LoopyButton("터치 감지 시작") {
                                scope.launch {
                                    val devs = reader.probe()
                                    if (devs.isEmpty()) {
                                        log.add(0, "터치 디바이스를 못 찾음 (getevent -pl 실패?)")
                                    } else {
                                        log.add(0, "── 발견한 디바이스 ${devs.size}개 (화면을 만져서 진짜를 찾자) ──")
                                        devs.forEach {
                                            log.add(0, "· ${it.name}  ${it.path}  max ${it.maxX}×${it.maxY}")
                                        }
                                        streaming = true
                                        reader.stream(scope, devs) { dev, p ->
                                            last = p
                                            lastDev = dev
                                            if (p.down) {
                                                log.add(
                                                    0,
                                                    "%s  %.3f, %.3f  (raw %d, %d)"
                                                        .format(dev.name, p.nx, p.ny, p.rawX, p.rawY)
                                                )
                                                if (log.size > 60) log.removeAt(log.size - 1)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            LoopyButton("정지", filled = false) {
                                reader.stop()
                                streaming = false
                            }
                        }
                    }
                    ShizukuState.NOT_INSTALLED -> {
                        LoopyButton("다시 확인") { state = ShizukuManager.state() }
                    }
                }
            }

            // ── 라이브 좌표 카드 ──
            GlassCard(Modifier.fillMaxWidth()) {
                Text("실시간 좌표", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val l = last
                val d = lastDev
                Text(
                    if (l != null && l.down) "● %.3f, %.3f".format(l.nx, l.ny) else "○ 대기 중",
                    color = if (l != null && l.down) LoopyViolet else TextLo,
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (d != null && l != null && l.down) {
                    Text(d.name, color = TextLo, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                ) {
                    if (log.isEmpty()) {
                        Text("화면을 만지면 좌표가 여기 찍혀.", color = TextLo, fontSize = 12.sp)
                    }
                    log.forEach { line ->
                        Text(line, color = TextLo, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoopyButton(text: String, filled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) LoopyViolet else Color(0x1AFFFFFF),
            contentColor = if (filled) Color.White else TextHi,
        ),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
