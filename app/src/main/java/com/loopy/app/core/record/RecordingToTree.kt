package com.loopy.app.core.record

import android.content.Context
import com.loopy.app.core.material.BuildParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.NoParams
import com.loopy.app.core.material.TouchParams
import com.loopy.app.core.material.WaitParams
import java.util.UUID

/**
 * 시각이 붙은 궤적. 트리로 바꾸기 전까지만 시각을 들고 있다.
 *
 * strokeId 가 있으면 이미 저장된 궤적이다. 편집 결과를 다시 트리로 접을 때 같은 id 를
 * 유지해야 저장소에 사본이 쌓이지 않는다.
 */
data class TimedStroke(val startMs: Long, val stroke: Stroke, val strokeId: String? = null)

/**
 * 녹화를 Material 트리로.
 *
 * 시각을 순서와 대기로 바꾼다. 스트로크가 스스로 시각을 들고 있으면 편집기에서만 의미가 있고
 * 빌드 화면에서는 해석할 수 없다. 순서축으로 바꾸면 두 화면이 같은 데이터를 본다.
 *
 * 규칙:
 *  - 시간이 겹치는 스트로크들은 한 그룹. 그룹은 parallel 로 묶고, 각 스트로크는 갈래가 된다.
 *  - 갈래가 그룹 시작보다 늦으면 그 앞에 대기를 둔다.
 *  - 그룹 사이 간격만큼 대기를 둔다. 간격 = 다음 그룹 시작 - 앞 그룹이 끝난 시각.
 */
object RecordingToTree {

    fun build(
        ctx: Context,
        timed: List<TimedStroke>,
        recordingId: String?,
        name: String,
    ): Material {
        val sorted = timed.sortedBy { it.startMs }
        val groups = groupOverlapping(sorted)

        val children = ArrayList<Material>()
        var cursor = 0L

        for (g in groups) {
            val groupStart = g.minOf { it.startMs }
            val gap = groupStart - cursor
            if (gap > 0) children.add(wait(gap))

            children.add(if (g.size == 1) touchOf(ctx, g[0]) else parallelOf(ctx, g, groupStart))

            cursor = g.maxOf { it.startMs + it.stroke.durationMs }
        }

        return Material(
            id = UUID.randomUUID().toString(),
            typeId = "build",
            params = BuildParams(recordingId),
            children = children,
            meta = Meta(name = name, createdAt = System.currentTimeMillis()),
        )
    }

    /** 시간이 맞물리는 것들을 한 덩어리로. 멀티터치는 겹쳐야 멀티터치다. */
    private fun groupOverlapping(sorted: List<TimedStroke>): List<List<TimedStroke>> {
        val groups = ArrayList<List<TimedStroke>>()
        var current = ArrayList<TimedStroke>()
        var groupEnd = -1L

        for (s in sorted) {
            val end = s.startMs + s.stroke.durationMs
            if (current.isEmpty() || s.startMs < groupEnd) {
                current.add(s)
                groupEnd = maxOf(groupEnd, end)
            } else {
                groups.add(current)
                current = arrayListOf(s)
                groupEnd = end
            }
        }
        if (current.isNotEmpty()) groups.add(current)
        return groups
    }

    /**
     * 동시 그룹.
     *
     * parallel 의 자식 하나가 곧 하나의 갈래이고, 갈래 안은 순서축이다.
     * 그래서 시차는 갈래 맨 앞의 대기로 표현된다. 오프셋 같은 별도 개념을 두지 않는 이유는,
     * "기다린다"는 뜻을 가진 블록이 이미 있는데 같은 일을 하는 두 번째 방법을 만들면
     * 나중에 어느 쪽을 봐야 할지 알 수 없게 되기 때문이다.
     */
    private fun parallelOf(ctx: Context, group: List<TimedStroke>, groupStart: Long): Material {
        val branches = group.map { ts ->
            val touch = touchOf(ctx, ts)
            val lead = ts.startMs - groupStart
            if (lead <= 0) {
                touch
            } else {
                // 갈래 자체가 순서축이므로, 대기와 터치를 담은 작은 순서 컨테이너로 감싼다.
                Material(
                    id = UUID.randomUUID().toString(),
                    typeId = "build",
                    params = BuildParams(null),
                    children = listOf(wait(lead), touch),
                )
            }
        }
        return Material(
            id = UUID.randomUUID().toString(),
            typeId = "parallel",
            params = NoParams,
            children = branches,
        )
    }

    private fun touchOf(ctx: Context, ts: TimedStroke): Material {
        val id = ts.strokeId ?: StrokeStore.put(ctx, ts.stroke)
        return Material(
            id = UUID.randomUUID().toString(),
            typeId = "touch",
            params = TouchParams(id),
        )
    }

    private fun wait(ms: Long) = Material(
        id = UUID.randomUUID().toString(),
        typeId = "wait",
        params = WaitParams(ms),
    )
}
