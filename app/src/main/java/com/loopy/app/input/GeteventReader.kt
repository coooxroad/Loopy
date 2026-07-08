package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 정규화된 터치 한 점 (0.0~1.0). slot = 손가락 번호(멀티터치 구분). */
data class TouchPoint(
    val slot: Int,
    val nx: Float,
    val ny: Float,
    val rawX: Int,
    val rawY: Int,
    val down: Boolean,
)

/** getevent -pl 로 찾아낸 터치 가능 디바이스. */
data class TouchDevice(
    val path: String,
    val name: String,
    val maxX: Int,
    val maxY: Int,
)

/**
 * getevent 로 물리 터치를 실시간 파싱한다. 멀티터치 프로토콜 B(슬롯) 처리.
 *
 * 정지 신뢰성: readLine() 은 블로킹이라 코루틴 cancel 만으론 안 멈춘다. 그래서
 *  1) 프로세스를 destroy 해 읽기를 끊고,
 *  2) "세대(gen)" 토큰으로 옛 스트림의 방출을 원천 차단한다.
 * stop() 이 gen 을 올리면, 그 이전에 시작된 스트림은 gen 불일치로 다시는 방출하지 못한다
 * (프로세스가 좀비로 남아도 무해). 새 녹화가 옛 스트림을 되살리는 일이 없다.
 */
class GeteventReader {

    private val jobs = mutableListOf<Job>()
    private val procs = mutableListOf<Process>()
    @Volatile private var gen = 0

    fun probe(): List<TouchDevice> {
        val out = Shell.run("getevent -pl") ?: return emptyList()
        var curPath: String? = null
        var curName = ""
        var maxX = -1
        var maxY = -1
        val found = mutableListOf<TouchDevice>()

        fun flush() {
            val p = curPath
            if (p != null && maxX > 0 && maxY > 0) found += TouchDevice(p, curName, maxX, maxY)
        }

        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("add device") -> {
                    flush()
                    curPath = line.substringAfter(": ", "").trim().ifEmpty { null }
                    curName = ""; maxX = -1; maxY = -1
                }
                line.startsWith("name:") -> curName = line.substringAfter("name:").trim().trim('"')
                line.contains("ABS_MT_POSITION_X") -> maxX = extractMax(line).coerceAtLeast(maxX)
                line.contains("ABS_MT_POSITION_Y") -> maxY = extractMax(line).coerceAtLeast(maxY)
            }
        }
        flush()
        return found.sortedByDescending { it.name.contains("touchscreen", ignoreCase = true) }
    }

    private fun extractMax(line: String): Int {
        val i = line.indexOf("max")
        if (i < 0) return -1
        val rest = line.substring(i + 3)
        val num = rest.dropWhile { !it.isDigit() && it != '-' }.takeWhile { it.isDigit() || it == '-' }
        return num.toIntOrNull() ?: -1
    }

    fun stream(
        scope: CoroutineScope,
        devices: List<TouchDevice>,
        onPoint: (TouchDevice, TouchPoint) -> Unit,
    ) {
        stop()
        val myGen = gen
        for (dev in devices) jobs += scope.launch(Dispatchers.IO) { streamOne(myGen, dev, onPoint) }
    }

    private class Slot(
        var x: Int = -1, var y: Int = -1, var down: Boolean = false,
        var id: Int = -1,
        var needUp: Boolean = false, var upX: Int = -1, var upY: Int = -1,
    )

    private fun streamOne(myGen: Int, dev: TouchDevice, onPoint: (TouchDevice, TouchPoint) -> Unit) {
        val proc = try {
            Shell.newProcess(arrayOf("sh", "-c", "getevent -lt ${dev.path}"))
        } catch (t: Throwable) {
            return
        }
        synchronized(procs) { procs.add(proc) }
        val slots = HashMap<Int, Slot>()
        val touched = HashSet<Int>()
        var curSlot = 0
        try {
            val br = proc.inputStream.bufferedReader()
            while (myGen == gen) {
                val line = br.readLine() ?: break
                val ev = parseLine(line) ?: continue
                when (ev.code) {
                    "ABS_MT_SLOT" -> { curSlot = ev.value; touched.add(curSlot) }
                    "ABS_MT_TRACKING_ID" -> {
                        val s = slots.getOrPut(curSlot) { Slot() }
                        val newId = if (ev.value == 0xffffffff.toInt() || ev.value == -1) -1 else ev.value
                        // 손가락 교체/뗌 감지: 이전 접촉이 down 이었는데 id 가 바뀌거나 -1 이면,
                        // 이번 SYN 에서 "이전 접촉의 up"을 반드시 먼저 방출(배칭돼도 안 놓침).
                        if (s.down && s.id != newId) {
                            s.needUp = true; s.upX = s.x; s.upY = s.y
                        }
                        s.id = newId
                        s.down = newId != -1
                        touched.add(curSlot)
                    }
                    "ABS_MT_POSITION_X" -> { slots.getOrPut(curSlot) { Slot() }.x = ev.value; touched.add(curSlot) }
                    "ABS_MT_POSITION_Y" -> { slots.getOrPut(curSlot) { Slot() }.y = ev.value; touched.add(curSlot) }
                    "SYN_REPORT" -> {
                        for (sl in touched) {
                            val s = slots[sl] ?: continue
                            if (myGen != gen) break
                            var upSent = false
                            // 1) 밀린 up 먼저(이전 접촉 종료) — 옛 좌표로.
                            if (s.needUp) {
                                if (s.upX in 0..dev.maxX && s.upY in 0..dev.maxY) {
                                    onPoint(dev, TouchPoint(sl, s.upX.toFloat() / dev.maxX, s.upY.toFloat() / dev.maxY, s.upX, s.upY, false))
                                }
                                s.needUp = false; upSent = true
                            }
                            // 2) 현재 상태 방출: down 이면 down, 아니면(이미 up 안 냈을 때만) up.
                            if (s.x in 0..dev.maxX && s.y in 0..dev.maxY) {
                                if (s.down) {
                                    onPoint(dev, TouchPoint(sl, s.x.toFloat() / dev.maxX, s.y.toFloat() / dev.maxY, s.x, s.y, true))
                                } else if (!upSent) {
                                    onPoint(dev, TouchPoint(sl, s.x.toFloat() / dev.maxX, s.y.toFloat() / dev.maxY, s.x, s.y, false))
                                }
                            }
                        }
                        touched.clear()
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            synchronized(procs) { procs.remove(proc) }
            runCatching { proc.destroy() }
        }
    }

    fun stop() {
        gen++ // 이 시점 이전에 시작된 모든 스트림의 방출을 무효화
        synchronized(procs) {
            procs.forEach { runCatching { it.destroy() } }
            procs.clear()
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private data class Ev(val type: String, val code: String, val value: Int)

    private fun parseLine(line: String): Ev? {
        val body = if (line.startsWith("[")) line.substringAfter("]").trim() else line.trim()
        val toks = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size < 3) return null
        if (!toks[0].startsWith("EV_")) return null
        val value = toks[2].toLongOrNull(16)?.toInt() ?: return null
        return Ev(toks[0], toks[1], value)
    }
}
