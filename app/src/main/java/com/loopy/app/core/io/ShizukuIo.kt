package com.loopy.app.core.io

import android.content.Context
import android.view.Display
import android.view.WindowManager
import com.loopy.app.input.GeteventReader
import com.loopy.app.core.record.Stroke
import com.loopy.app.core.record.StrokeStore
import com.loopy.app.service.LoopyService
import com.loopy.app.shizuku.Shell
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Shizuku 기반 [Io] 구현.
 *
 * 터치 주입·캡처·셸·설정 접근이 여러 화면에 흩어져 있던 것을 여기로 모은다.
 * 호출부(오버레이, 편집기, 앞으로 추가될 Material 실행기)는 Io 인터페이스만 알면 되고,
 * Shizuku 의 세부(바인더, getevent 스트림, 좌표 변환)를 알 필요가 없다.
 */
class ShizukuIo(
    private val context: Context,
    private val scope: CoroutineScope,
) : Io {

    private val reader = GeteventReader()

    /** 동시에 주입 중인 손가락 번호들. parallel 갈래마다 다른 번호를 써야 뭉개지지 않는다. */
    private val busyFingers = HashSet<Int>()

    companion object {
        /** 오버레이 서비스가 자기 구현을 여기에 걸어둔다. */
        @Volatile
        var dimHandler: ((Boolean) -> Unit)? = null

        @Volatile
        var dimState: Boolean = false
    }

    override val available: Boolean
        get() = ShizukuManager.state() == ShizukuState.READY && LoopyService.isReady()

    // ── 입력 ──

    /**
     * 궤적 하나 재생.
     *
     * parallel 갈래들이 동시에 이 함수를 부를 수 있으므로 손가락 id 를 겹치지 않게 배정한다.
     * 같은 id 로 두 궤적을 주입하면 시스템이 하나의 손가락으로 보고 둘을 뭉갠다.
     */
    override suspend fun playStroke(stroke: Stroke) {
        if (stroke.samples.isEmpty()) return
        val (w, h) = screenSize()
        val rot = if (stroke.rotation >= 0) stroke.rotation else rotation()

        val n = stroke.samples.size
        val xs = IntArray(n)
        val ys = IntArray(n)
        val ts = LongArray(n)
        for (i in 0 until n) {
            val p = stroke.samples[i]
            val (px, py) = toPixels(p.nx, p.ny, rot, w, h)
            xs[i] = px
            ys[i] = py
            ts[i] = p.t
        }

        val finger = acquireFinger()
        try {
            withContext(Dispatchers.IO) {
                LoopyService.playMulti(
                    intArrayOf(finger),
                    longArrayOf(0L),
                    longArrayOf(stroke.durationMs),
                    intArrayOf(n),
                    xs, ys, ts,
                )
            }
        } finally {
            releaseFinger(finger)
        }
    }

    /** 여러 궤적을 손가락 여러 개로 묶어 한 번에 주입한다(진짜 멀티터치). 녹화 재생과 같은 경로. */
    override suspend fun playStrokesById(items: List<Pair<Long, String>>) {
        if (items.isEmpty()) return
        val (w, h) = screenSize()
        val defRot = rotation()

        val fingerIds = ArrayList<Int>()
        val startMs = ArrayList<Long>()
        val durationsMs = ArrayList<Long>()
        val sampleCounts = ArrayList<Int>()
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val ts = ArrayList<Long>()

        var finger = 0
        for ((lead, strokeId) in items) {
            val stroke = StrokeStore.get(context, strokeId) ?: continue
            if (stroke.samples.isEmpty()) continue
            val rot = if (stroke.rotation >= 0) stroke.rotation else defRot
            fingerIds.add(finger)
            startMs.add(lead)
            durationsMs.add(stroke.durationMs)
            sampleCounts.add(stroke.samples.size)
            for (p in stroke.samples) {
                val (px, py) = toPixels(p.nx, p.ny, rot, w, h)
                xs.add(px); ys.add(py); ts.add(p.t)
            }
            finger++
        }
        if (fingerIds.isEmpty()) return

        withContext(Dispatchers.IO) {
            LoopyService.playMulti(
                fingerIds.toIntArray(),
                startMs.toLongArray(),
                durationsMs.toLongArray(),
                sampleCounts.toIntArray(),
                xs.toIntArray(), ys.toIntArray(), ts.toLongArray(),
            )
        }
    }

    /** 지금 쓰이지 않는 가장 작은 손가락 번호. */
    private fun acquireFinger(): Int = synchronized(busyFingers) {
        var i = 0
        while (i in busyFingers) i++
        busyFingers.add(i)
        i
    }

    private fun releaseFinger(id: Int) = synchronized(busyFingers) {
        busyFingers.remove(id)
        Unit
    }

    override fun stopPlayback() {
        // 주입은 원격 프로세스에서 도는 중이라, 여기서는 새 호출을 막고 상위가 코루틴을 취소한다.
        // 별도 중단 API 가 생기면 여기에 연결한다.
    }

    override fun startCapture(onPoint: (Int, Float, Float, Boolean) -> Unit) {
        val devices = runCatching { reader.probe() }.getOrDefault(emptyList())
        val device = devices.firstOrNull { it.name.contains("touchscreen", true) }
            ?: devices.firstOrNull()
            ?: return
        reader.stream(scope, listOf(device)) { _, p ->
            onPoint(p.slot, p.nx, p.ny, p.down)
        }
    }

    override fun stopCapture() {
        reader.stop()
    }

    // ── 화면 ──

    @Suppress("DEPRECATION")
    override fun rotation(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay.rotation * 90
    }

    /**
     * 실제 화면 전체 크기.
     *
     * displayMetrics 는 네비게이션 바를 뺀 앱 영역만 준다. 터치는 그 바깥에서도 일어나므로
     * 여기서는 반드시 getRealMetrics 를 써야 한다. 앱 영역 기준으로 좌표를 풀면 화면이
     * 짧아진 만큼 터치가 위로 밀린다.
     */
    @Suppress("DEPRECATION")
    override fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /**
     * 화면 캡처.
     *
     * 이미지 인식 Material 이 여기에 의존한다. Shizuku 셸의 screencap 으로 뽑을 수 있지만
     * 매 프레임 호출하기엔 느리므로, 실제 구현 시에는 MediaProjection 재사용을 검토한다.
     */
    override suspend fun captureScreen(): ScreenShot? = null

    // ── 시스템 ──

    override suspend fun putSetting(namespace: String, key: String, value: String): Boolean =
        shell("settings put $namespace $key $value") != null

    override suspend fun getSetting(namespace: String, key: String): String? =
        shell("settings get $namespace $key")?.trim()

    override suspend fun shell(cmd: String): String? = withContext(Dispatchers.IO) {
        runCatching { Shell.run(cmd) }.getOrNull()
    }

    /**
     * 화면 가리기.
     *
     * 윈도우를 띄우는 일은 오버레이 서비스만 할 수 있으므로, 실제 동작은 그쪽이 등록한
     * 핸들러에 위임한다. Io 를 쓰는 쪽(Material 실행기)은 서비스의 존재를 몰라도 된다.
     */
    override fun setDim(on: Boolean) {
        dimHandler?.invoke(on)
    }

    override fun isDimmed(): Boolean = dimState

    override suspend fun dismissAlarm(): Boolean =
        shell("am broadcast -a com.android.deskclock.ALARM_DISMISS") != null ||
            shell("input keyevent KEYCODE_STOP") != null

    override suspend fun launchApp(pkg: String): Boolean =
        shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1") != null

    override suspend fun forceStop(pkg: String): Boolean =
        shell("am force-stop $pkg") != null

    override suspend fun foregroundApp(): String? {
        val out = shell("dumpsys activity activities | grep -m1 mResumedActivity") ?: return null
        // "mResumedActivity: ActivityRecord{... com.pkg/.Activity ...}" 에서 패키지만 뽑는다.
        return Regex("""\s([a-zA-Z0-9_.]+)/""").find(out)?.groupValues?.get(1)
    }

    /**
     * 패널 정규화 좌표 → 화면 픽셀.
     *
     * 재생 측과 편집기 측의 회전 규칙이 어긋나면 좌표가 틀어지므로,
     * 규칙 자체는 core/geom/Coords 와 동일해야 한다.
     */
    private fun toPixels(nx: Float, ny: Float, rot: Int, w: Int, h: Int): Pair<Int, Int> =
        when (rot) {
            90 -> (ny * w).roundToInt() to ((1 - nx) * h).roundToInt()
            180 -> ((1 - nx) * w).roundToInt() to ((1 - ny) * h).roundToInt()
            270 -> ((1 - ny) * w).roundToInt() to (nx * h).roundToInt()
            else -> (nx * w).roundToInt() to (ny * h).roundToInt()
        }
}
