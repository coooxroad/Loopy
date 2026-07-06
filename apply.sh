#!/data/data/com.termux/files/usr/bin/bash
# Loopy: 탭/홀드를 press(좌표+실제 누른시간)로 통합
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

mkdir -p "$(dirname "app/src/main/aidl/com/loopy/app/service/ILoopyService.aidl")"
cat > "app/src/main/aidl/com/loopy/app/service/ILoopyService.aidl" << 'LOOPY_EOF'
// Shizuku UserService 인터페이스. elevated(shell) 프로세스에서 실행되는 메서드들.
package com.loopy.app.service;

interface ILoopyService {
    // Shizuku 서버가 서비스를 종료할 때 호출하는 예약 트랜잭션 ID (고정).
    void destroy() = 16777114;
    void exit() = 1;

    // 좌표를 durationMs 동안 누르고 뗌. 탭(짧게)/홀드(길게) 통합.
    void press(int x, int y, int durationMs) = 2;

    // (x1,y1) → (x2,y2) 로 durationMs 동안 스와이프.
    void swipe(int x1, int y1, int x2, int y2, int durationMs) = 3;
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/service/LoopyUserService.kt")"
cat > "app/src/main/java/com/loopy/app/service/LoopyUserService.kt" << 'LOOPY_EOF'
package com.loopy.app.service

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku UserService 본체. shell 프로세스에서 injectInputEvent 로 터치를 주입한다.
 * (scrcpy 방식: InputManagerGlobal → getInstance → injectInputEvent, source=TOUCHSCREEN)
 *
 * press(x, y, durationMs): 좌표를 durationMs 만큼 누르고 뗀다. 사용자가 실제로 누른
 * 시간을 그대로 재생하므로, 짧게 톡 친 건 짧게 / 꾹 누른 건 길게 = 원본과 동일.
 * (0ms 순간 탭은 런처/앱이 인식 못 하는 문제도 자연히 해결된다.)
 */
class LoopyUserService : ILoopyService.Stub() {

    private val instance: Any
    private val injectMethod: Method

    init {
        val cls = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }
            .getOrElse { Class.forName("android.hardware.input.InputManager") }
        val getInstance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
        instance = getInstance.invoke(null)!!
        injectMethod = runCatching {
            instance.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        }.getOrElse {
            instance.javaClass.getDeclaredMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        }.apply { isAccessible = true }
    }

    private fun inject(ev: InputEvent) {
        injectMethod.invoke(instance, ev, 0) // 0 = ASYNC
    }

    private fun motion(downTime: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return ev
    }

    override fun press(x: Int, y: Int, durationMs: Int) {
        runCatching {
            val t = SystemClock.uptimeMillis()
            val down = motion(t, t, MotionEvent.ACTION_DOWN, x, y)
            inject(down); down.recycle()
            // 최소 20ms 는 유지(순간탭 인식 실패 방지). 그 이상은 사용자가 누른 시간 그대로.
            Thread.sleep(durationMs.toLong().coerceAtLeast(20L))
            val t2 = SystemClock.uptimeMillis()
            val up = motion(t, t2, MotionEvent.ACTION_UP, x, y)
            inject(up); up.recycle()
        }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        runCatching {
            val steps = (durationMs / 10).coerceIn(2, 100)
            val downTime = SystemClock.uptimeMillis()
            val down = motion(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1)
            inject(down); down.recycle()
            for (i in 1..steps) {
                val f = i.toFloat() / steps
                val x = (x1 + (x2 - x1) * f).toInt()
                val y = (y1 + (y2 - y1) * f).toInt()
                Thread.sleep((durationMs / steps).toLong().coerceAtLeast(1))
                val now = SystemClock.uptimeMillis()
                val move = motion(downTime, now, MotionEvent.ACTION_MOVE, x, y)
                inject(move); move.recycle()
            }
            val end = SystemClock.uptimeMillis()
            val up = motion(downTime, end, MotionEvent.ACTION_UP, x2, y2)
            inject(up); up.recycle()
        }
    }

    override fun exit() {
        destroy()
    }

    override fun destroy() {
        exitProcess(0)
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/service/LoopyService.kt")"
cat > "app/src/main/java/com/loopy/app/service/LoopyService.kt" << 'LOOPY_EOF'
package com.loopy.app.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * 앱 프로세스에서 Shizuku UserService(LoopyUserService)를 바인딩하고 호출을 넘겨주는 싱글톤.
 * 실제 injectInputEvent 는 shell 프로세스(LoopyUserService)에서 일어난다.
 *
 * tap/swipe 은 바인더 호출이라 호출 스레드를 잠깐 붙잡으므로 IO 스레드에서 부를 것.
 * 반환값은 "서비스가 연결돼 있어 호출을 보냈는지" (false 면 아직 미연결/실패).
 */
object LoopyService {

    @Volatile private var svc: ILoopyService? = null
    @Volatile private var binding = false

    fun isReady(): Boolean = svc != null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            svc = if (binder != null && binder.pingBinder()) {
                ILoopyService.Stub.asInterface(binder)
            } else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    private fun args(context: Context) =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, LoopyUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("loopy")
            .debuggable(false)
            .version(1)

    /** Shizuku 권한이 허용된 뒤 호출. 이미 연결됐거나 진행 중이면 무시. */
    fun bind(context: Context) {
        if (svc != null || binding) return
        binding = true
        runCatching { Shizuku.bindUserService(args(context.applicationContext), conn) }
            .onFailure { binding = false }
    }

    fun press(x: Int, y: Int, durationMs: Int): Boolean =
        runCatching { svc?.press(x, y, durationMs); svc != null }.getOrDefault(false)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        runCatching { svc?.swipe(x1, y1, x2, y2, durationMs); svc != null }.getOrDefault(false)
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
 * 판정: 이동거리 >= MOVE_THRESH → 스와이프 / 그 외 → PRESS(좌표 + 실제 누른 시간).
 * 좌표는 panel 정규화(0~1)로 저장. 여러 손가락이 동시에 눌려도, 재생은 시작 시각 순서로
 * 순차 실행하므로 타이핑 순서가 보존된다.
 */
class GestureRecorder {

    enum class Type { PRESS, SWIPE }

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
                val type = if (dist >= MOVE_THRESH) Type.SWIPE else Type.PRESS
                // 디바운스: 직전 PRESS 와 아주 짧은 시간 + 거의 같은 자리면 슬롯 재사용
                // 경계에서 생긴 중복이므로 버린다.
                if (type == Type.PRESS) {
                    val last = raws.lastOrNull()
                    if (last != null && last.type == Type.PRESS &&
                        (t.downT - last.endT) in 0L until DEDUP_MS &&
                        hypot((t.downX - last.x).toDouble(), (t.downY - last.y).toDouble()) < DEDUP_DIST
                    ) {
                        return
                    }
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
        // 디바운스: 이 시간 미만 + 이 거리 미만의 연속 PRESS 는 중복으로 간주.
        const val DEDUP_MS = 50L
        const val DEDUP_DIST = 0.02
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/macro/MacroStore.kt")"
cat > "app/src/main/java/com/loopy/app/macro/MacroStore.kt" << 'LOOPY_EOF'
package com.loopy.app.macro

import android.content.Context
import com.loopy.app.input.GestureRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 매크로를 앱 내부 저장소에 JSON 파일로 저장/관리한다. (filesDir/macros/{id}.json)
 * 직렬화는 안드로이드 기본 내장 org.json 사용 — 의존성 추가 없음.
 */
object MacroStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "macros").apply { mkdirs() }

    /** "Jun 14 AM 2:00" 형식 자동 이름. */
    fun autoName(time: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MMM d a h:mm", Locale.ENGLISH).format(Date(time))

    /** actions 로 새 매크로를 만들어 저장하고 반환. */
    fun saveNew(ctx: Context, actions: List<GestureRecorder.Action>): Macro {
        val now = System.currentTimeMillis()
        val macro = Macro(UUID.randomUUID().toString(), autoName(now), now, actions)
        write(ctx, macro)
        return macro
    }

    fun rename(ctx: Context, id: String, newName: String) {
        val m = read(ctx, id) ?: return
        write(ctx, m.copy(name = newName))
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    /** 최신순 목록. 손상된 파일은 건너뜀. */
    fun list(ctx: Context): List<Macro> =
        (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun read(ctx: Context, id: String): Macro? =
        runCatching { fromJson(File(dir(ctx), "$id.json").readText()) }.getOrNull()

    private fun write(ctx: Context, macro: Macro) {
        File(dir(ctx), "${macro.id}.json").writeText(toJson(macro))
    }

    // ── 직렬화 ──
    private fun toJson(m: Macro): String {
        val arr = JSONArray()
        for (a in m.actions) {
            arr.put(
                JSONObject()
                    .put("delayMs", a.delayMs)
                    .put("type", a.type.name)
                    .put("x", a.x.toDouble()).put("y", a.y.toDouble())
                    .put("x2", a.x2.toDouble()).put("y2", a.y2.toDouble())
                    .put("durationMs", a.durationMs)
            )
        }
        return JSONObject()
            .put("id", m.id)
            .put("name", m.name)
            .put("createdAt", m.createdAt)
            .put("actions", arr)
            .toString()
    }

    private fun fromJson(text: String): Macro {
        val o = JSONObject(text)
        val arr = o.getJSONArray("actions")
        val actions = ArrayList<GestureRecorder.Action>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            actions.add(
                GestureRecorder.Action(
                    delayMs = a.getLong("delayMs"),
                    type = when (a.getString("type")) {
                        "SWIPE" -> GestureRecorder.Type.SWIPE
                        else -> GestureRecorder.Type.PRESS // 기존 TAP/HOLD → PRESS
                    },
                    x = a.getDouble("x").toFloat(),
                    y = a.getDouble("y").toFloat(),
                    x2 = a.getDouble("x2").toFloat(),
                    y2 = a.getDouble("y2").toFloat(),
                    durationMs = a.getLong("durationMs"),
                )
            )
        }
        return Macro(
            id = o.getString("id"),
            name = o.getString("name"),
            createdAt = o.getLong("createdAt"),
            actions = actions,
        )
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
import com.loopy.app.input.GestureRecorder
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.TouchDevice
import com.loopy.app.macro.Macro
import com.loopy.app.macro.MacroStore
import com.loopy.app.macro.Playlist
import com.loopy.app.macro.PlaylistStore
import com.loopy.app.service.LoopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 매크로/플레이리스트 컨트롤 오버레이.
 *  - 녹화: getevent → GestureRecorder. 정지 시 자동 저장.
 *  - 재생: 저장 매크로 하나, 또는 플레이리스트(셔플백 + N회/무한)를 injectInputEvent 로 주입.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private val reader = GeteventReader()
    private val recorder = GestureRecorder()
    private var device: TouchDevice? = null
    private var recording = false
    private var playJob: Job? = null

    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var recordBtn: Button
    private lateinit var stopPlayBtn: TextView
    private var listPanel: LinearLayout? = null

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

    private fun addControlBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = pill(0xF2FFFFFF.toInt(), dp(18))
            elevation = dp(6).toFloat()
        }
        val title = TextView(this).apply {
            text = "Loopy"; setTextColor(0xFF2B2D42.toInt()); textSize = 15f
        }
        status = TextView(this).apply {
            setTextColor(0xFF8A8DA0.toInt()); textSize = 11f; text = "녹화를 눌러 시작"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordBtn = pillButton("● 녹화", 0xFFFF7A6E.toInt()) { toggleRecord() }
        val playBtn = pillButton("▶ 재생", 0xFF6C7BFF.toInt()) { playRecorded() }
        val listBtn = pillButton("📁", 0xFFECECF2.toInt(), 0xFF2B2D42.toInt()) { toggleList() }
        row.addView(recordBtn)
        row.addView(playBtn, marginLeft(dp(8)))
        row.addView(listBtn, marginLeft(dp(8)))

        stopPlayBtn = TextView(this).apply {
            text = "■ 재생 정지"; setTextColor(0xFFFF5A4E.toInt()); textSize = 12f
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
            setOnClickListener { stopPlayback("정지됨") }
        }
        val closeBtn = TextView(this).apply {
            text = "닫기"; setTextColor(0xFF8A8DA0.toInt()); textSize = 11f
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { stopSelf() }
        }

        bar.addView(title)
        bar.addView(status)
        bar.addView(row)
        bar.addView(stopPlayBtn)
        bar.addView(closeBtn)

        barParams = baseParams().apply { x = dp(12); y = dp(60) }
        makeDraggable(title)
        wm.addView(bar, barParams)
    }

    private fun pillButton(label: String, bg: Int, fg: Int = 0xFFFFFFFF.toInt(), onClick: () -> Unit) =
        Button(this).apply {
            text = label; setTextColor(fg); background = pill(bg, dp(12))
            setOnClickListener { onClick() }
        }

    private fun marginLeft(px: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = px }

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
        stopPlayback(null)
        val devs = reader.probe()
        val dev = devs.firstOrNull { it.name.contains("touchscreen", true) } ?: devs.firstOrNull()
        if (dev == null) { status.text = "터치 디바이스를 못 찾음"; return }
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
        val snap = recorder.snapshot()
        if (snap.isEmpty()) { status.text = "행동 없음 (저장 안 함)"; return }
        val m = MacroStore.saveNew(this, snap)
        status.text = "저장됨: ${m.name} · ${snap.size}개"
    }

    // ── 재생 ──
    private fun playRecorded() {
        if (recording) stopRecord()
        startSingle(recorder.snapshot(), "지금 녹화")
    }

    private fun startSingle(list: List<GestureRecorder.Action>, label: String) {
        if (recording) stopRecord()
        stopPlayback(null)
        if (list.isEmpty()) { status.text = "재생할 행동이 없어"; return }
        stopPlayBtn.visibility = View.VISIBLE
        status.text = "▶ 재생중… $label"
        playJob = scope.launch {
            runActions(list)
            status.text = "재생 끝 · $label"
            stopPlayBtn.visibility = View.GONE
            playJob = null
        }
    }

    private fun playPlaylist(pl: Playlist) {
        if (recording) stopRecord()
        stopPlayback(null)
        val macros = HashMap<String, Macro>()
        pl.macroIds.toSet().forEach { id -> MacroStore.read(this, id)?.let { macros[id] = it } }
        if (macros.isEmpty()) { status.text = "매크로가 비어있어"; return }
        stopPlayBtn.visibility = View.VISIBLE
        playJob = scope.launch {
            var cycle = 0
            while (isActive && (pl.cycles == 0 || cycle < pl.cycles)) {
                val order = if (pl.shuffle) pl.macroIds.shuffled() else pl.macroIds
                for (id in order) {
                    if (!isActive) break
                    val m = macros[id] ?: continue
                    val total = if (pl.cycles > 0) "/${pl.cycles}" else ""
                    status.text = "▶ ${pl.name} · ${cycle + 1}$total · ${m.name}"
                    runActions(m.actions)
                    if (pl.gapMs > 0) delay(pl.gapMs.toLong())
                }
                cycle++
            }
            status.text = "플레이리스트 끝 · ${pl.name}"
            stopPlayBtn.visibility = View.GONE
            playJob = null
        }
    }

    private fun stopPlayback(msg: String?) {
        playJob?.cancel()
        playJob = null
        stopPlayBtn.visibility = View.GONE
        if (msg != null) status.text = msg
    }

    /** 한 매크로의 액션들을 현재 화면 방향에 맞춰 순차 주입. 취소 가능(delay). */
    private suspend fun runActions(list: List<GestureRecorder.Action>) {
        val m = DisplayMetrics()
        displayObj.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels
        val rot = displayObj.rotation
        for (a in list) {
            if (!coroutineContext.isActive) return
            delay(a.delayMs)
            val (x, y) = toPx(a.x, a.y, w, h, rot)
            withContext(Dispatchers.IO) {
                when (a.type) {
                    GestureRecorder.Type.PRESS -> LoopyService.press(x, y, a.durationMs.toInt())
                    GestureRecorder.Type.SWIPE -> {
                        val (x2, y2) = toPx(a.x2, a.y2, w, h, rot)
                        LoopyService.swipe(x, y, x2, y2, a.durationMs.toInt().coerceAtLeast(50))
                    }
                }
            }
        }
    }

    // ── 저장 목록 (드롭다운: 플레이리스트 + 매크로) ──
    private fun toggleList() {
        listPanel?.let { bar.removeView(it); listPanel = null; return }
        val playlists = PlaylistStore.list(this)
        val macros = MacroStore.list(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0)
        }
        if (playlists.isEmpty() && macros.isEmpty()) {
            panel.addView(hint("저장된 게 없어"))
        } else {
            if (playlists.isNotEmpty()) {
                panel.addView(hint("─ 플레이리스트 ─"))
                for (pl in playlists) {
                    panel.addView(listRow("▶▶ ${pl.name} (${pl.macroIds.size})", 0xFF6C7BFF.toInt()) {
                        toggleList(); playPlaylist(pl)
                    })
                }
            }
            if (macros.isNotEmpty()) {
                panel.addView(hint("─ 매크로 ─"))
                for (mac in macros) {
                    panel.addView(listRow("▶ ${mac.name} (${mac.actions.size})", 0xFF2B2D42.toInt()) {
                        toggleList(); startSingle(mac.actions, mac.name)
                    })
                }
            }
        }
        bar.addView(panel)
        listPanel = panel
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF8A8DA0.toInt()); textSize = 10f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun listRow(t: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(color); textSize = 12f
        setPadding(0, dp(7), 0, dp(7))
        setOnClickListener { onClick() }
    }

    private fun toPx(u: Float, v: Float, w: Int, h: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> (v * w).toInt() to ((1 - u) * h).toInt()
        Surface.ROTATION_180 -> ((1 - u) * w).toInt() to ((1 - v) * h).toInt()
        Surface.ROTATION_270 -> ((1 - v) * w).toInt() to (u * h).toInt()
        else -> (u * w).toInt() to (v * h).toInt()
    }

    private fun barContains(u: Float, v: Float): Boolean {
        val m = DisplayMetrics()
        displayObj.getRealMetrics(m)
        val (px, py) = toPx(u, v, m.widthPixels, m.heightPixels, displayObj.rotation)
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height).contains(px, py)
    }

    private fun makeDraggable(handle: View) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = barParams.x; startY = barParams.y; touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    barParams.x = startX + (e.rawX - touchX).toInt()
                    barParams.y = startY + (e.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(bar, barParams) }; true
                }
                else -> false
            }
        }
    }

    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
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

echo "반영."
git add -A
git commit -m "press 통합: 실제 누른 시간 기록/재생 (탭/홀드 일원화)"
git push
echo "푸시 완료!"

