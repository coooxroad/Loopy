package com.loopy.app.core.stroke

import com.loopy.app.core.record.PlacedStroke
import com.loopy.app.core.record.Stroke
import com.loopy.app.core.record.TouchSample

/**
 * 스트로크 편집 연산.
 *
 * UI 와 무관한 순수 함수라 편집기 밖(빌드 화면, 스크립트, 일괄 처리)에서도 쓸 수 있어야 한다.
 * 잘라내기·분할은 샘플을 실제로 버리므로 재생 결과가 함께 바뀐다. 화면에서만 잘린 척하면
 * 편집한 대로 돌지 않아 도구를 믿을 수 없게 된다.
 */
object StrokeOps {

    /**
     * 기준 시각 이전을 버린다.
     *
     * 궤적 자체는 시각을 모르므로 시작 시각을 함께 받는다. 남는 샘플이 2개 미만이면
     * 궤적이 성립하지 않으므로 null 을 돌려 편집을 거절한다.
     *
     * @return 새 궤적과 새 시작 시각
     */
    fun trimLeft(s: Stroke, startMs: Long, atMs: Long): Pair<Stroke, Long>? {
        val rel = (atMs - startMs).coerceIn(0L, s.durationMs)
        val kept = s.samples.filter { it.t >= rel }.map { it.copy(t = it.t - rel) }
        if (kept.size < 2) return null
        return s.copy(durationMs = s.durationMs - rel, samples = kept) to (startMs + rel)
    }

    /** 기준 시각 이후를 버린다. 시작 시각은 그대로다. */
    fun trimRight(s: Stroke, startMs: Long, atMs: Long): Stroke? {
        val rel = (atMs - startMs).coerceIn(0L, s.durationMs)
        val kept = s.samples.filter { it.t <= rel }
        if (kept.size < 2) return null
        return s.copy(durationMs = rel, samples = kept)
    }

    /** 한 궤적을 둘로. 각각 독립적으로 옮기고 지울 수 있게 된다. */
    fun split(s: Stroke, startMs: Long, atMs: Long): Pair<Pair<Stroke, Long>, Pair<Stroke, Long>>? {
        val left = trimRight(s, startMs, atMs) ?: return null
        val right = trimLeft(s, startMs, atMs) ?: return null
        return (left to startMs) to right
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
    fun assignLanes(placed: List<PlacedStroke>): List<Int> {
        val lanes = IntArray(placed.size)
        val laneEnd = ArrayList<Long>()
        for (i in placed.indices.sortedBy { placed[it].startMs }) {
            val p = placed[i]
            var lane = laneEnd.indexOfFirst { it <= p.startMs }
            if (lane < 0) {
                lane = laneEnd.size
                laneEnd.add(0L)
            }
            laneEnd[lane] = p.startMs + p.stroke.durationMs
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

}
