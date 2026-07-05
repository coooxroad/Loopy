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
