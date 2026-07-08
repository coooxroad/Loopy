package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * 터치를 실시간으로 읽는다.
 *
 * 기본: /dev/input/eventX 를 **바이너리로 직접** 읽어 struct input_event 를 파싱한다
 * (64비트 = 24바이트/이벤트). getevent 텍스트 파싱과 달리 배칭·손실이 없어 정확도 100%에 가깝다.
 * 폴백: 바이너리가 즉시 실패(권한/EOF)하면 기존 getevent 텍스트 파싱으로 자동 전환.
 *
 * 정지 신뢰성: 프로세스를 destroy 하고 "세대(gen)" 토큰으로 옛 스트림 방출을 원천 차단한다.
 */
class GeteventReader {

    private val jobs = mutableListOf<Job>()
    private val procs = mutableListOf<Process>()
    @Volatile private var gen = 0

    // ── struct input_event (64bit) ──
    private companion object {
        const val EV_SIZE = 24        // timeval(16) + type(2) + code(2) + value(4)
        const val EV_SYN = 0x00
        const val EV_ABS = 0x03
        const val SYN_REPORT = 0x00
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
    }

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

    /** 바이너리 리더(우선). 즉시 실패 시 텍스트 폴백. */
    private fun streamOne(myGen: Int, dev: TouchDevice, onPoint: (TouchDevice, TouchPoint) -> Unit) {
        val proc = try {
            Shell.newProcess(arrayOf("sh", "-c", "cat ${dev.path}"))
        } catch (t: Throwable) {
            streamOneText(myGen, dev, onPoint); return
        }
        synchronized(procs) { procs.add(proc) }
        val slots = HashMap<Int, Slot>()
        val touched = HashSet<Int>()
        var curSlot = 0
        var anyEvent = false
        val buf = ByteArray(EV_SIZE)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val input = proc.inputStream
            while (myGen == gen) {
                // 정확히 24바이트(부분 읽기 처리)
                var off = 0
                while (off < EV_SIZE) {
                    val n = input.read(buf, off, EV_SIZE - off)
                    if (n < 0) {
                        // EOF: 이벤트를 하나도 못 읽었으면 텍스트로 폴백
                        if (!anyEvent) { cleanup(proc); streamOneText(myGen, dev, onPoint); return }
                        return
                    }
                    off += n
                }
                anyEvent = true
                bb.clear()
                bb.position(16) // timeval 건너뜀
                val type = bb.short.toInt() and 0xFFFF
                val code = bb.short.toInt() and 0xFFFF
                val value = bb.int
                if (myGen != gen) break
                if (type == EV_ABS) {
                    when (code) {
                        ABS_MT_SLOT -> { curSlot = value; touched.add(curSlot) }
                        ABS_MT_TRACKING_ID -> {
                            val s = slots.getOrPut(curSlot) { Slot() }
                            val newId = if (value == -1) -1 else value
                            if (s.down && s.id != newId) { s.needUp = true; s.upX = s.x; s.upY = s.y }
                            s.id = newId
                            s.down = newId != -1
                            touched.add(curSlot)
                        }
                        ABS_MT_POSITION_X -> { slots.getOrPut(curSlot) { Slot() }.x = value; touched.add(curSlot) }
                        ABS_MT_POSITION_Y -> { slots.getOrPut(curSlot) { Slot() }.y = value; touched.add(curSlot) }
                    }
                } else if (type == EV_SYN && code == SYN_REPORT) {
                    emit(dev, myGen, slots, touched, onPoint)
                    touched.clear()
                }
            }
        } catch (_: Throwable) {
            if (!anyEvent) { cleanup(proc); streamOneText(myGen, dev, onPoint); return }
        } finally {
            cleanup(proc)
        }
    }

    /** 공통 방출 로직(밀린 up 먼저 → 현재 상태). */
    private fun emit(
        dev: TouchDevice, myGen: Int,
        slots: HashMap<Int, Slot>, touched: HashSet<Int>,
        onPoint: (TouchDevice, TouchPoint) -> Unit,
    ) {
        for (sl in touched) {
            val s = slots[sl] ?: continue
            if (myGen != gen) break
            var upSent = false
            if (s.needUp) {
                if (s.upX in 0..dev.maxX && s.upY in 0..dev.maxY) {
                    onPoint(dev, TouchPoint(sl, s.upX.toFloat() / dev.maxX, s.upY.toFloat() / dev.maxY, s.upX, s.upY, false))
                }
                s.needUp = false; upSent = true
            }
            if (s.x in 0..dev.maxX && s.y in 0..dev.maxY) {
                if (s.down) {
                    onPoint(dev, TouchPoint(sl, s.x.toFloat() / dev.maxX, s.y.toFloat() / dev.maxY, s.x, s.y, true))
                } else if (!upSent) {
                    onPoint(dev, TouchPoint(sl, s.x.toFloat() / dev.maxX, s.y.toFloat() / dev.maxY, s.x, s.y, false))
                }
            }
        }
    }

    private fun cleanup(proc: Process) {
        synchronized(procs) { procs.remove(proc) }
        runCatching { proc.destroy() }
    }

    /** 폴백: 기존 getevent -lt 텍스트 파싱. */
    private fun streamOneText(myGen: Int, dev: TouchDevice, onPoint: (TouchDevice, TouchPoint) -> Unit) {
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
                        if (s.down && s.id != newId) { s.needUp = true; s.upX = s.x; s.upY = s.y }
                        s.id = newId
                        s.down = newId != -1
                        touched.add(curSlot)
                    }
                    "ABS_MT_POSITION_X" -> { slots.getOrPut(curSlot) { Slot() }.x = ev.value; touched.add(curSlot) }
                    "ABS_MT_POSITION_Y" -> { slots.getOrPut(curSlot) { Slot() }.y = ev.value; touched.add(curSlot) }
                    "SYN_REPORT" -> { emit(dev, myGen, slots, touched, onPoint); touched.clear() }
                }
            }
        } catch (_: Throwable) {
        } finally {
            cleanup(proc)
        }
    }

    fun stop() {
        gen++
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
