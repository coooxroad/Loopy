#!/data/data/com.termux/files/usr/bin/bash
# Loopy fix: 녹화 정지가 실제로 안 멈추던 버그
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

cat > "app/src/main/java/com/loopy/app/input/GeteventReader.kt" << 'LOOPY_EOF'
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
 * getevent 로 물리 터치를 실시간 파싱한다. 멀티터치 프로토콜 B 를 처리한다:
 *  - ABS_MT_SLOT 로 현재 슬롯(손가락) 지정
 *  - 슬롯별로 ABS_MT_TRACKING_ID(내려감/뗌), POSITION_X/Y(위치)
 *  - SYN_REPORT 프레임마다 이번에 바뀐 슬롯들을 각각 방출
 * → 손가락이 겹쳐도 각 손가락을 독립적으로 추적할 수 있다.
 */
class GeteventReader {

    private val jobs = mutableListOf<Job>()
    private val procs = mutableListOf<Process>()
    @Volatile private var active = false

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
        active = true
        for (dev in devices) jobs += scope.launch(Dispatchers.IO) { streamOne(dev, onPoint) }
    }

    private class Slot(var x: Int = -1, var y: Int = -1, var down: Boolean = false)

    private fun streamOne(dev: TouchDevice, onPoint: (TouchDevice, TouchPoint) -> Unit) {
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
            proc.inputStream.bufferedReader().forEachLine { line ->
                val ev = parseLine(line) ?: return@forEachLine
                when (ev.code) {
                    "ABS_MT_SLOT" -> {
                        curSlot = ev.value
                        touched.add(curSlot)
                    }
                    "ABS_MT_TRACKING_ID" -> {
                        val s = slots.getOrPut(curSlot) { Slot() }
                        s.down = ev.value != 0xffffffff.toInt() && ev.value != -1
                        touched.add(curSlot)
                    }
                    "ABS_MT_POSITION_X" -> {
                        slots.getOrPut(curSlot) { Slot() }.x = ev.value; touched.add(curSlot)
                    }
                    "ABS_MT_POSITION_Y" -> {
                        slots.getOrPut(curSlot) { Slot() }.y = ev.value; touched.add(curSlot)
                    }
                    "SYN_REPORT" -> {
                        for (sl in touched) {
                            val s = slots[sl] ?: continue
                            if (active && s.x in 0..dev.maxX && s.y in 0..dev.maxY) {
                                onPoint(
                                    dev,
                                    TouchPoint(
                                        slot = sl,
                                        nx = s.x.toFloat() / dev.maxX,
                                        ny = s.y.toFloat() / dev.maxY,
                                        rawX = s.x, rawY = s.y,
                                        down = s.down,
                                    ),
                                )
                            }
                        }
                        touched.clear()
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { proc.destroy() }
        }
    }

    fun stop() {
        active = false
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
LOOPY_EOF

echo "반영."
git add -A
git commit -m "fix: 녹화 정지 시 getevent 프로세스 종료 (정지 후 입력이 계속 녹화되던 버그)"
git push
echo "푸시 완료!"

