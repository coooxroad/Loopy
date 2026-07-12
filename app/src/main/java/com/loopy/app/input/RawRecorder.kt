package com.loopy.app.input

import android.os.SystemClock
import com.loopy.app.macro.Stroke
import com.loopy.app.macro.TouchSample
import java.util.Collections

/**
 * 터치 포인트를 슬롯(손가락)별로 모아 스트로크로 만든다.
 *
 * 제스처를 분류하지 않는다. 손가락이 내려온 순간부터 떨어질 때까지의 좌표를 그대로 담으므로
 * 탭·홀드·스와이프·조이스틱이 별도 처리 없이 재현된다. 입력을 있는 그대로 기록하는 것이
 * 어떤 휴리스틱보다 정확하다.
 *
 * 예전에는 "사람이 낼 수 없는 움직임"을 추측해 스트로크를 강제로 쪼갰다. 드라이버가 손 뗌을
 * 알리지 않는 경우를 대비한 것이었지만, [GeteventReader] 가 ABS_MT_TRACKING_ID 로 접촉 교체를
 * 정확히 잡게 된 뒤로는 필요가 없어졌다. 오히려 손가락을 가만히 누르고 있으면 좌표 이벤트가
 * 오지 않아 긴 홀드가 조각나는 부작용만 남았다. 그래서 전부 걷어냈다.
 */
class RawRecorder {

    /** 패널 좌표가 무시 대상(컨트롤 바 위)인지. */
    var shouldIgnore: (Float, Float) -> Boolean = { _, _ -> false }

    private class Builder(val startT: Long, val downX: Float, val downY: Float) {
        val samples = ArrayList<TouchSample>()
        var lastT: Long = startT
    }

    private data class Done(
        val startT: Long,
        val endT: Long,
        val downX: Float,
        val downY: Float,
        val samples: List<TouchSample>,
    )

    private val tracks = HashMap<Int, Builder>()
    private val done = Collections.synchronizedList(mutableListOf<Done>())

    fun reset() {
        tracks.clear()
        done.clear()
    }

    /** Io 통로에서 오는 원시 좌표. 호출부가 TouchPoint 를 알 필요 없게 한다. */
    fun onPoint(slot: Int, nx: Float, ny: Float, down: Boolean) =
        onPoint(TouchPoint(slot, nx, ny, 0, 0, down))

    fun onPoint(p: TouchPoint) {
        val now = SystemClock.uptimeMillis()
        val b = tracks[p.slot]
        when {
            // 손가락이 내려옴
            p.down && b == null -> {
                tracks[p.slot] = Builder(now, p.nx, p.ny).apply {
                    samples.add(TouchSample(0L, p.nx, p.ny))
                    lastT = now
                }
            }
            // 눌린 채 이동. 같은 자리에 머무는 동안에도 이벤트가 오면 그대로 남긴다.
            p.down && b != null -> {
                b.samples.add(TouchSample(now - b.startT, p.nx, p.ny))
                b.lastT = now
            }
            // 손가락이 떨어짐
            !p.down && b != null -> {
                tracks.remove(p.slot)
                if (shouldIgnore(b.downX, b.downY)) return
                if (b.samples.isNotEmpty()) {
                    done.add(Done(b.startT, now, b.downX, b.downY, b.samples.toList()))
                }
            }
        }
    }

    /** 마지막 snapshot 의 기준 시각(첫 이벤트의 절대 uptimeMillis). 영상 싱크에 쓴다. -1 = 없음. */
    @Volatile
    var baseUptime: Long = -1L
        private set

    fun snapshot(): List<Stroke> {
        val sorted = synchronized(done) { done.toList() }.sortedBy { it.startT }
        if (sorted.isEmpty()) {
            baseUptime = -1L
            return emptyList()
        }
        // 가장 이른 스트로크를 0 기준으로 삼는다. 스트로크 간 상대 시각이 유지되어야
        // 동시 멀티터치가 재생 때도 겹친다.
        val base = sorted.first().startT
        baseUptime = base
        return sorted.map { d ->
            Stroke(
                startMs = (d.startT - base).coerceAtLeast(0L),
                durationMs = (d.endT - d.startT).coerceAtLeast(0L),
                samples = d.samples,
            )
        }
    }
}
