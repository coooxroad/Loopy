package com.loopy.app.core.io

import android.content.Context
import android.view.Display
import android.view.WindowManager
import com.loopy.app.input.GeteventReader
import com.loopy.app.macro.Stroke
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

    private companion object {
        /** UP 직후 같은 id 를 재사용하기까지의 최소 간격. 연타가 하나로 합쳐지는 것을 막는다. */
        const val FINGER_REUSE_GAP_MS = 12L
    }

    override val available: Boolean
        get() = ShizukuManager.state() == ShizukuState.READY && LoopyService.isReady()

    // ── 입력 ──

    override suspend fun playStrokes(strokes: List<Stroke>, rotationAt: (Long) -> Int) {
        if (strokes.isEmpty()) return
        val (w, h) = screenSize()
        val n = strokes.size

        // 손가락 id 배정.
        //
        // 스트로크마다 새 id 를 주면 활성 포인터가 계속 늘어나 시스템이 입력을 뭉갠다.
        // 시간이 겹치지 않는 스트로크끼리는 같은 id 를 재사용하되, UP 과 다음 DOWN 사이에
        // 최소 간격(12ms)을 둔다. 이 간격이 없으면 빠른 연타가 하나의 터치로 합쳐진다.
        val fingerIds = IntArray(n)
        val freeAt = ArrayList<Long>()
        for (k in strokes.indices) {
            val s = strokes[k]
            val end = s.startMs + maxOf(s.durationMs, s.samples.lastOrNull()?.t ?: 0L)
            var assigned = -1
            for (id in freeAt.indices) {
                if (freeAt[id] + FINGER_REUSE_GAP_MS <= s.startMs) {
                    assigned = id
                    break
                }
            }
            if (assigned == -1) {
                assigned = freeAt.size
                freeAt.add(end)
            } else {
                freeAt[assigned] = end
            }
            fingerIds[k] = assigned
        }

        val starts = LongArray(n) { strokes[it].startMs }
        val durs = LongArray(n) { strokes[it].durationMs }
        val counts = IntArray(n) { strokes[it].samples.size }

        val total = counts.sum()
        val xs = IntArray(total)
        val ys = IntArray(total)
        val ts = LongArray(total)

        var k = 0
        for (s in strokes) {
            // 스트로크마다 그 시점의 회전으로 좌표를 푼다. 녹화 중 기기를 돌린 구간도 맞게 재생된다.
            val rot = if (s.rotation >= 0) s.rotation else rotationAt(s.startMs)
            for (p in s.samples) {
                val (px, py) = toPixels(p.nx, p.ny, rot, w, h)
                xs[k] = px
                ys[k] = py
                ts[k] = p.t
                k++
            }
        }

        withContext(Dispatchers.IO) {
            LoopyService.playMulti(fingerIds, starts, durs, counts, xs, ys, ts)
        }
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
