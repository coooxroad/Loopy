package com.loopy.app.input

import android.os.SystemClock
import com.loopy.app.core.record.Stroke
import com.loopy.app.core.record.TimedStroke
import com.loopy.app.core.record.TouchSample
import java.util.Collections

/**
 * 터치 포인트를 슬롯(손가락)별로 모아 궤적으로 만든다.
 *
 * 제스처를 분류하지 않는다. 손가락이 내려온 순간부터 떨어질 때까지의 좌표를 그대로 담으므로
 * 탭·홀드·스와이프·조이스틱이 별도 처리 없이 재현된다. 입력을 있는 그대로 기록하는 것이
 * 어떤 휴리스틱보다 정확하다.
 *
 * 예전에는 "사람이 낼 수 없는 움직임"을 추측해 궤적을 강제로 쪼갰다. [GeteventReader] 가
 * 접촉 교체를 정확히 잡게 된 뒤로는 필요가 없어졌고, 오히려 손가락을 가만히 누르고 있을 때
 * 긴 홀드가 조각나는 부작용만 남아 전부 걷어냈다.
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
        val rotation: Int,
    )

    private val tracks = HashMap<Int, Builder>()
    private val done = Collections.synchronizedList(mutableListOf<Done>())

    /** 지금 화면 회전. 녹화 중 기기를 돌리면 궤적마다 다른 값이 붙는다. */
    var currentRotation: () -> Int = { 0 }

    fun reset() {
        tracks.clear()
        done.clear()
    }

    fun onPoint(slot: Int, nx: Float, ny: Float, down: Boolean) =
        onPoint(TouchPoint(slot, nx, ny, 0, 0, down))

    fun onPoint(p: TouchPoint) {
        val now = SystemClock.uptimeMillis()
        val b = tracks[p.slot]
        when {
            p.down && b == null -> {
                tracks[p.slot] = Builder(now, p.nx, p.ny).apply {
                    samples.add(TouchSample(0L, p.nx, p.ny))
                    lastT = now
                }
            }

            p.down && b != null -> {
                b.samples.add(TouchSample(now - b.startT, p.nx, p.ny))
                b.lastT = now
            }

            !p.down && b != null -> {
                tracks.remove(p.slot)
                if (shouldIgnore(b.downX, b.downY)) return
                if (b.samples.isNotEmpty()) {
                    done.add(
                        Done(b.startT, now, b.downX, b.downY, b.samples.toList(), currentRotation()),
                    )
                }
            }
        }
    }

    /** 첫 이벤트의 절대 uptimeMillis. 영상 싱크에 쓴다. -1 = 없음. */
    @Volatile
    var baseUptime: Long = -1L
        private set

    /**
     * 기록된 궤적들.
     *
     * 궤적 자체는 시각을 갖지 않는다. 언제 재생될지는 트리가 정하므로, 여기서는 시각을
     * 따로 실어 [TimedStroke] 로 넘긴다. 트리로 변환되고 나면 이 시각은 순서와 대기가 된다.
     */
    fun snapshot(): List<TimedStroke> {
        val sorted = synchronized(done) { done.toList() }.sortedBy { it.startT }
        if (sorted.isEmpty()) {
            baseUptime = -1L
            return emptyList()
        }
        val base = sorted.first().startT
        baseUptime = base
        return sorted.map { d ->
            TimedStroke(
                startMs = (d.startT - base).coerceAtLeast(0L),
                stroke = Stroke(
                    durationMs = (d.endT - d.startT).coerceAtLeast(0L),
                    samples = d.samples,
                    rotation = d.rotation,
                ),
            )
        }
    }
}
