package com.loopy.app.core.stroke

import com.loopy.app.macro.Macro
import com.loopy.app.macro.Stroke
import com.loopy.app.macro.TouchSample

/**
 * 스트로크 편집 연산.
 *
 * UI 와 무관한 순수 함수라 편집기 밖(빌드 화면, 스크립트, 일괄 처리)에서도 쓸 수 있어야 한다.
 * 잘라내기·분할은 샘플을 실제로 버리므로 재생 결과가 함께 바뀐다. 화면에서만 잘린 척하면
 * 편집한 대로 돌지 않아 도구를 믿을 수 없게 된다.
 */
object StrokeOps {

    /** 기준 시각 이전을 버린다. 남는 샘플이 2개 미만이면 스트로크가 성립하지 않는다. */
    fun trimLeft(s: Stroke, atMs: Long): Stroke? {
        val rel = (atMs - s.startMs).coerceIn(0L, s.durationMs)
        val kept = s.samples.filter { it.t >= rel }.map { it.copy(t = it.t - rel) }
        if (kept.size < 2) return null
        return s.copy(startMs = s.startMs + rel, durationMs = s.durationMs - rel, samples = kept)
    }

    /** 기준 시각 이후를 버린다. */
    fun trimRight(s: Stroke, atMs: Long): Stroke? {
        val rel = (atMs - s.startMs).coerceIn(0L, s.durationMs)
        val kept = s.samples.filter { it.t <= rel }
        if (kept.size < 2) return null
        return s.copy(durationMs = rel, samples = kept)
    }

    /** 한 스트로크를 둘로. 각각 독립적으로 옮기고 지울 수 있게 된다. */
    fun split(s: Stroke, atMs: Long): Pair<Stroke, Stroke>? {
        val left = trimRight(s, atMs) ?: return null
        val right = trimLeft(s, atMs) ?: return null
        return left to right
    }

    /** 스트로크 전체를 평행이동. 모양은 유지하고 위치만 옮긴다. */
    fun move(s: Stroke, dnx: Float, dny: Float): Stroke = s.copy(
        samples = s.samples.map {
            it.copy(nx = (it.nx + dnx).coerceIn(0f, 1f), ny = (it.ny + dny).coerceIn(0f, 1f))
        },
    )

    /**
     * 겹치는 스트로크를 세로 레인으로 분리.
     * 멀티터치는 시간이 겹치므로 한 줄에 그리면 서로 가린다.
     */
    fun assignLanes(strokes: List<Stroke>): List<Int> {
        val lanes = IntArray(strokes.size)
        val laneEnd = ArrayList<Long>()
        for (i in strokes.indices.sortedBy { strokes[it].startMs }) {
            val s = strokes[i]
            var lane = laneEnd.indexOfFirst { it <= s.startMs }
            if (lane < 0) {
                lane = laneEnd.size
                laneEnd.add(0L)
            }
            laneEnd[lane] = s.startMs + s.durationMs
            lanes[i] = lane
        }
        return lanes.toList()
    }

    /** 시각 t 에서의 좌표. 샘플 사이는 선형 보간한다. */
    fun sampleAt(samples: List<TouchSample>, t: Long): Pair<Float, Float>? {
        if (samples.isEmpty()) return null
        val first = samples.first()
        val last = samples.last()
        if (t <= first.t) return first.nx to first.ny
        if (t >= last.t) return last.nx to last.ny
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            if (t in a.t..b.t) {
                val f = if (b.t == a.t) 0f else (t - a.t).toFloat() / (b.t - a.t)
                return (a.nx + (b.nx - a.nx) * f) to (a.ny + (b.ny - a.ny) * f)
            }
        }
        return last.nx to last.ny
    }

    /**
     * 매크로 시각 tMs 에서의 화면 회전.
     * 녹화 중 기기를 돌리면 좌표계가 바뀌므로, 그 시점의 회전을 알아야 제대로 그리고 재생한다.
     */
    fun rotationAt(macro: Macro, tMs: Long): Int {
        val events = macro.rotationEvents
        if (events.isEmpty()) return macro.rotation
        var rot = events.first().rotation
        for (e in events) {
            if (e.tMs <= tMs) rot = e.rotation else break
        }
        return rot
    }
}
