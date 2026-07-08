package com.loopy.app.input

import android.os.SystemClock
import com.loopy.app.macro.Stroke
import com.loopy.app.macro.TouchSample
import java.util.Collections
import kotlin.math.hypot

/**
 * getevent 포인트를 슬롯(손가락)별로 보고 "좌표 타임라인 스트로크"를 통째로 기록한다.
 * 제스처 분류 없음 — 손가락이 그린 모든 미세 이동을 그대로 담으므로 탭/홀드/스와이프/
 * 조이스틱이 전부 자연히 재현된다.
 *
 * (A1=단일 손가락 재현) 여러 손가락이 겹치면 각 슬롯의 스트로크를 따로 기록하고
 * 재생은 시작 시각 순으로 순차 실행한다. 동시 멀티터치는 다음 단계.
 */
class RawRecorder {

    companion object {
        // 정규화(0~1) 거리. 근본적인 손 뗌 감지는 GeteventReader가 처리하고,
        // 이건 드라이버가 접촉 교체를 아예 안 알리는 극단 케이스용 안전망(넉넉히).
        private const val JUMP_SPLIT = 0.30
    }

    /** panel 좌표(u,v)가 무시 대상(컨트롤 바 위)인지. */
    var shouldIgnore: (Float, Float) -> Boolean = { _, _ -> false }

    private class Builder(val startT: Long, val downX: Float, val downY: Float) {
        val samples = ArrayList<TouchSample>()
    }

    private data class Done(val startT: Long, val endT: Long, val downX: Float, val downY: Float, val samples: List<TouchSample>)

    private val tracks = HashMap<Int, Builder>()
    private val done = Collections.synchronizedList(mutableListOf<Done>())

    fun reset() {
        tracks.clear()
        done.clear()
    }

    fun count(): Int = done.size

    fun onPoint(p: TouchPoint) {
        val now = SystemClock.uptimeMillis()
        val b = tracks[p.slot]
        when {
            p.down && b == null -> {
                val nb = Builder(now, p.nx, p.ny)
                nb.samples.add(TouchSample(0L, p.nx, p.ny))
                tracks[p.slot] = nb
            }
            p.down && b != null -> {
                // 순간이동 감지: 한 샘플 만에 화면의 큰 거리를 점프하면 물리적으로 불가능 →
                // 실은 별개의 두 터치(놓친 up)로 보고 스트로크를 강제 분리한다.
                val last = b.samples.last()
                val jump = hypot((p.nx - last.nx).toDouble(), (p.ny - last.ny).toDouble())
                if (jump > JUMP_SPLIT) {
                    tracks.remove(p.slot)
                    if (!shouldIgnore(b.downX, b.downY) && b.samples.isNotEmpty()) {
                        done.add(Done(b.startT, now, b.downX, b.downY, b.samples.toList()))
                    }
                    val nb = Builder(now, p.nx, p.ny)
                    nb.samples.add(TouchSample(0L, p.nx, p.ny))
                    tracks[p.slot] = nb
                } else {
                    b.samples.add(TouchSample(now - b.startT, p.nx, p.ny))
                }
            }
            !p.down && b != null -> {
                tracks.remove(p.slot)
                if (shouldIgnore(b.downX, b.downY)) return
                if (b.samples.isNotEmpty()) {
                    done.add(Done(b.startT, now, b.downX, b.downY, b.samples.toList()))
                }
            }
        }
    }

    /** 시작 시각 순 정렬 + 스트로크 사이 대기 계산. */
    fun snapshot(): List<Stroke> {
        val sorted = synchronized(done) { done.toList() }.sortedBy { it.startT }
        if (sorted.isEmpty()) return emptyList()
        val base = sorted.first().startT // 제일 이른 스트로크를 0 기준으로
        val out = ArrayList<Stroke>(sorted.size)
        for (d in sorted) {
            out.add(
                Stroke(
                    startMs = (d.startT - base).coerceAtLeast(0L),
                    durationMs = (d.endT - d.startT).coerceAtLeast(0L),
                    samples = d.samples,
                )
            )
        }
        return out
    }
}
