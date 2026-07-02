package com.loopy.app.input

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
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

/** getevent -pl 로 찾아낸 터치스크린 정보. */
data class TouchDevice(
    val path: String,       // /dev/input/eventX
    val name: String,
    val maxX: Int,
    val maxY: Int,
)

/**
 * M0의 핵심. Shizuku 셸로 getevent 를 돌려 물리 터치를 실시간 파싱한다.
 *
 * 두 단계:
 *  1) probe(): `getevent -pl` 을 한 번 실행해 ABS_MT_POSITION_X/Y 를 가진 디바이스와
 *     그 max 값을 찾는다. 이 max 로 raw 좌표를 0~1 로 정규화한다(해상도 독립성의 핵심).
 *  2) stream(): 찾은 디바이스 하나만 `getevent -lt <path>` 로 스트리밍 파싱한다.
 *
 * 재생 쪽(injectInputEvent)과 달리 getevent 는 /dev/input 하드웨어 레이어를 직접 읽으므로,
 * 우리가 주입한 이벤트는 여기 안 잡히고 물리 터치만 잡힌다 → 화면 끄기 모드에서
 * "물리 터치 감지"와 "매크로 주입"을 깨끗하게 분리할 수 있는 근거.
 */
class GeteventReader {

    private var job: Job? = null

    // Shizuku.newProcess 는 이 API 버전에서 private 이라 리플렉션으로 호출한다.
    // (rikka.shizuku.* 는 프레임워크 클래스가 아니라 hidden-API 제약이 없고,
    //  debug 빌드라 난독화도 없어 안전하다. 반환형 ShizukuRemoteProcess 는
    //  java.lang.Process 를 상속하므로 Process 로 받아 쓴다.)
    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    private fun newShizukuProcess(cmd: Array<String>): Process =
        newProcessMethod.invoke(null, cmd, null, null) as Process

    /** getevent -pl 결과에서 터치스크린 디바이스를 찾는다. 없으면 null. */
    fun probe(): TouchDevice? {
        val out = runBlockingShell("getevent -pl") ?: return null

        var curPath: String? = null
        var curName = ""
        var maxX = -1
        var maxY = -1
        val candidates = mutableListOf<TouchDevice>()

        fun flush() {
            val p = curPath
            if (p != null && maxX > 0 && maxY > 0) {
                candidates += TouchDevice(p, curName, maxX, maxY)
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

        // max 범위가 가장 큰 놈을 실제 터치스크린으로 채택 (버튼/근접센서 등 노이즈 배제)
        return candidates.maxByOrNull { it.maxX.toLong() * it.maxY.toLong() }
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
     * 디바이스 하나를 스트리밍 파싱. onPoint 는 SYN_REPORT 마다(=한 프레임) 호출된다.
     * scope 가 취소되거나 stop() 하면 프로세스를 죽인다.
     */
    fun stream(
        scope: CoroutineScope,
        dev: TouchDevice,
        onPoint: (TouchPoint) -> Unit,
    ) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            val proc = try {
                newShizukuProcess(arrayOf("sh", "-c", "getevent -lt ${dev.path}"))
            } catch (t: Throwable) {
                return@launch
            }
            var curX = -1
            var curY = -1
            var down = false
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        parseLine(line)?.let { ev ->
                            when (ev.code) {
                                "ABS_MT_POSITION_X" -> curX = ev.value
                                "ABS_MT_POSITION_Y" -> curY = ev.value
                                "ABS_MT_TRACKING_ID" ->
                                    down = ev.value != 0xffffffff.toInt() && ev.value != -1
                                "BTN_TOUCH" -> down = ev.value == 1
                                "SYN_REPORT" -> {
                                    if (curX in 0..dev.maxX && curY in 0..dev.maxY) {
                                        onPoint(
                                            TouchPoint(
                                                nx = curX.toFloat() / dev.maxX,
                                                ny = curY.toFloat() / dev.maxY,
                                                rawX = curX,
                                                rawY = curY,
                                                down = down,
                                            )
                                        )
                                    }
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
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private data class Ev(val type: String, val code: String, val value: Int)

    /**
     * `[   86185.123456] EV_ABS       ABS_MT_POSITION_X    000007a1`
     * 형태를 파싱. 값은 16진수. 디바이스 경로를 인자로 준 스트림이라 앞 접두어는 없다.
     */
    private fun parseLine(line: String): Ev? {
        // '[' 타임스탬프 블록 제거
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
            val proc = newShizukuProcess(arrayOf("sh", "-c", cmd))
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text
        } catch (t: Throwable) {
            null
        }
    }
}
