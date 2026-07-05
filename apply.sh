#!/data/data/com.termux/files/usr/bin/bash
# Loopy: 멀티터치(슬롯) 처리 — 빠른 타이핑 뭉개짐 수정
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/GeteventReader.kt")"
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
        for (dev in devices) jobs += scope.launch(Dispatchers.IO) { streamOne(dev, onPoint) }
    }

    private class Slot(var x: Int = -1, var y: Int = -1, var down: Boolean = false)

    private fun streamOne(dev: TouchDevice, onPoint: (TouchDevice, TouchPoint) -> Unit) {
        val proc = try {
            Shell.newProcess(arrayOf("sh", "-c", "getevent -lt ${dev.path}"))
        } catch (t: Throwable) {
            return
        }
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
                            if (s.x in 0..dev.maxX && s.y in 0..dev.maxY) {
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

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/GestureRecorder.kt")"
cat > "app/src/main/java/com/loopy/app/input/GestureRecorder.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import android.os.SystemClock
import java.util.Collections
import kotlin.math.hypot

/**
 * getevent 포인트(슬롯별)를 손가락 단위로 보고 탭/홀드/스와이프로 판정한다.
 * 손가락이 겹쳐도(빠른 타이핑) 슬롯마다 독립적으로 추적하므로 뭉개지지 않는다.
 *
 * 판정: 이동거리 >= MOVE_THRESH → 스와이프 / 누른 시간 >= HOLD_THRESH_MS → 홀드 / 그 외 탭.
 * 좌표는 panel 정규화(0~1)로 저장. 여러 손가락이 동시에 눌려도, 재생은 시작 시각 순서로
 * 순차 실행하므로 타이핑 순서가 보존된다.
 */
class GestureRecorder {

    enum class Type { TAP, HOLD, SWIPE }

    data class Action(
        val delayMs: Long,
        val type: Type,
        val x: Float, val y: Float,
        val x2: Float, val y2: Float,
        val durationMs: Long,
    )

    private data class Raw(
        val startT: Long, val endT: Long, val type: Type,
        val x: Float, val y: Float, val x2: Float, val y2: Float, val durationMs: Long,
    )

    private class Track(val downT: Long, val downX: Float, val downY: Float) {
        var curX = downX
        var curY = downY
    }

    private val raws = Collections.synchronizedList(mutableListOf<Raw>())
    private val tracks = HashMap<Int, Track>()

    /** panel 좌표(u,v)가 무시 대상(컨트롤 바 위)인지. */
    var shouldIgnore: (Float, Float) -> Boolean = { _, _ -> false }

    fun reset() {
        raws.clear()
        tracks.clear()
    }

    fun count(): Int = raws.size

    fun onPoint(p: TouchPoint) {
        val now = SystemClock.uptimeMillis()
        val t = tracks[p.slot]
        when {
            p.down && t == null -> tracks[p.slot] = Track(now, p.nx, p.ny)
            p.down && t != null -> { t.curX = p.nx; t.curY = p.ny }
            !p.down && t != null -> {
                tracks.remove(p.slot)
                if (shouldIgnore(t.downX, t.downY)) return
                val dur = now - t.downT
                val dist = hypot((t.curX - t.downX).toDouble(), (t.curY - t.downY).toDouble()).toFloat()
                val type = when {
                    dist >= MOVE_THRESH -> Type.SWIPE
                    dur >= HOLD_THRESH_MS -> Type.HOLD
                    else -> Type.TAP
                }
                raws.add(Raw(t.downT, now, type, t.downX, t.downY, t.curX, t.curY, dur))
            }
        }
    }

    /** 시작 시각 순으로 정렬하고 행동 사이 대기시간을 계산한 재생용 리스트. */
    fun snapshot(): List<Action> {
        val sorted = synchronized(raws) { raws.toList() }.sortedBy { it.startT }
        val result = ArrayList<Action>(sorted.size)
        var prevEnd = 0L
        for (r in sorted) {
            val delay = if (result.isEmpty()) 0L else (r.startT - prevEnd).coerceAtLeast(0L)
            result.add(Action(delay, r.type, r.x, r.y, r.x2, r.y2, r.durationMs))
            prevEnd = r.endT
        }
        return result
    }

    companion object {
        const val MOVE_THRESH = 0.03f
        const val HOLD_THRESH_MS = 400L
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/overlay/OverlayService.kt")"
cat > "app/src/main/java/com/loopy/app/overlay/OverlayService.kt" << 'LOOPY_EOF'
package com.loopy.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.GestureRecorder
import com.loopy.app.input.TouchDevice
import com.loopy.app.service.LoopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 매크로 컨트롤 오버레이. (조준점 탭 테스트는 제거됨)
 *
 * 녹화: getevent 로 사용자의 실제 터치를 읽어 GestureRecorder 로 탭/홀드/스와이프 판정.
 *       컨트롤 바 위의 터치는 무시(shouldIgnore).
 * 재생: 저장된 Action 을 현재 화면 방향에 맞게 픽셀로 변환해 injectInputEvent 로 주입.
 *
 * 저장(파일)은 다음 단계. 지금은 메모리에서 녹화→재생 왕복 검증.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private val reader = GeteventReader()
    private val recorder = GestureRecorder()
    private var device: TouchDevice? = null
    private var recording = false

    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var recordBtn: Button
    private lateinit var playBtn: Button

    private val displayObj by lazy {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        LoopyService.bind(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        recorder.shouldIgnore = { u, v -> barContains(u, v) }
        addControlBar()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── 컨트롤 바 ──
    private fun addControlBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = pill(0xF2FFFFFF.toInt(), dp(18))
            elevation = dp(6).toFloat()
        }
        val title = TextView(this).apply {
            text = "Loopy"
            setTextColor(0xFF2B2D42.toInt())
            textSize = 15f
        }
        status = TextView(this).apply {
            setTextColor(0xFF8A8DA0.toInt())
            textSize = 11f
            text = "녹화를 눌러 시작"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordBtn = Button(this).apply {
            text = "● 녹화"
            setTextColor(0xFFFFFFFF.toInt())
            background = pill(0xFFFF7A6E.toInt(), dp(12))
            setOnClickListener { toggleRecord() }
        }
        playBtn = Button(this).apply {
            text = "▶ 재생"
            setTextColor(0xFFFFFFFF.toInt())
            background = pill(0xFF6C7BFF.toInt(), dp(12))
            setOnClickListener { play() }
        }
        row.addView(recordBtn)
        row.addView(
            playBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )
        val closeBtn = TextView(this).apply {
            text = "닫기"
            setTextColor(0xFF8A8DA0.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { stopSelf() }
        }

        bar.addView(title)
        bar.addView(status)
        bar.addView(row)
        bar.addView(closeBtn)

        barParams = baseParams().apply { x = dp(12); y = dp(60) }
        makeDraggable(title)
        wm.addView(bar, barParams)
    }

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    // ── 녹화 ──
    private fun toggleRecord() {
        if (!recording) startRecord() else stopRecord()
    }

    private fun startRecord() {
        val devs = reader.probe()
        val dev = devs.firstOrNull { it.name.contains("touchscreen", true) } ?: devs.firstOrNull()
        if (dev == null) {
            status.text = "터치 디바이스를 못 찾음"
            return
        }
        device = dev
        recorder.reset()
        recording = true
        recordBtn.text = "■ 정지"
        status.text = "● 녹화중 — 평소처럼 플레이해"
        reader.stream(scope, listOf(dev)) { _, p -> recorder.onPoint(p) }
    }

    private fun stopRecord() {
        reader.stop()
        recording = false
        recordBtn.text = "● 녹화"
        status.text = "행동 ${recorder.count()}개 · 재생 가능"
    }

    // ── 재생 ──
    private fun play() {
        if (recording) stopRecord()
        val list = recorder.snapshot()
        if (list.isEmpty()) {
            status.text = "녹화된 행동이 없어"
            return
        }
        status.text = "▶ 재생중… (${list.size}개)"
        scope.launch {
            val m = DisplayMetrics()
            displayObj.getRealMetrics(m)
            val w = m.widthPixels
            val h = m.heightPixels
            val rot = displayObj.rotation
            for (a in list) {
                delay(a.delayMs)
                val (x, y) = toPx(a.x, a.y, w, h, rot)
                withContext(Dispatchers.IO) {
                    when (a.type) {
                        GestureRecorder.Type.TAP -> LoopyService.tap(x, y)
                        GestureRecorder.Type.HOLD -> LoopyService.hold(x, y, a.durationMs.toInt())
                        GestureRecorder.Type.SWIPE -> {
                            val (x2, y2) = toPx(a.x2, a.y2, w, h, rot)
                            LoopyService.swipe(x, y, x2, y2, a.durationMs.toInt().coerceAtLeast(50))
                        }
                    }
                }
            }
            status.text = "재생 끝 · 행동 ${list.size}개"
        }
    }

    /** panel 정규화(u,v) → 현재 방향의 화면 픽셀. getevent 는 회전 전 패널 좌표라 보정 필요. */
    private fun toPx(u: Float, v: Float, w: Int, h: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> (v * w).toInt() to ((1 - u) * h).toInt()
        Surface.ROTATION_180 -> ((1 - u) * w).toInt() to ((1 - v) * h).toInt()
        Surface.ROTATION_270 -> ((1 - v) * w).toInt() to (u * h).toInt()
        else -> (u * w).toInt() to (v * h).toInt()
    }

    /** panel(u,v) 가 컨트롤 바 위인지 (녹화에서 무시하려고). */
    private fun barContains(u: Float, v: Float): Boolean {
        val m = DisplayMetrics()
        displayObj.getRealMetrics(m)
        val (px, py) = toPx(u, v, m.widthPixels, m.heightPixels, displayObj.rotation)
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height)
        return rect.contains(px, py)
    }

    private fun makeDraggable(handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = barParams.x; startY = barParams.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    barParams.x = startX + (e.rawX - touchX).toInt()
                    barParams.y = startY + (e.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(bar, barParams) }
                    true
                }
                else -> false
            }
        }
    }

    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun startAsForeground() {
        val channelId = "loopy_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Loopy 오버레이", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Loopy 실행 중")
            .setContentText("매크로 컨트롤이 화면에 떠 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reader.stop()
        scope.cancel()
        runCatching { wm.removeView(bar) }
    }
}
LOOPY_EOF

echo "3개 반영."
git add -A
git commit -m "fix: 멀티터치 슬롯 처리 (겹친 탭이 스와이프로 오판되던 버그)"
git push
echo "푸시 완료!"

