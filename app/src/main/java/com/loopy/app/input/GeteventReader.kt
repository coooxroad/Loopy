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
            newShizukuProcess(arrayOf("sh", "-c", "getevent -lt ${dev.path}"))
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
            val proc = newShizukuProcess(arrayOf("sh", "-c", cmd))
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            text
        } catch (t: Throwable) {
            null
        }
    }
}
