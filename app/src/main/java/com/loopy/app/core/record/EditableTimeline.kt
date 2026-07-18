package com.loopy.app.core.record

import android.content.Context
import com.loopy.app.core.material.Kind
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.TypeKinds

/**
 * 편집기가 다루는 시간축 모델.
 *
 * 트리는 순서와 대기로 타이밍을 표현하지만, 편집기는 "몇 초 지점에 무엇이 있는가"로 봐야 한다.
 * 이 클래스가 그 두 관점을 오간다. 편집기가 트리를 직접 만지면 대기 값을 매번 다시 계산해야
 * 하고, 그 계산이 편집기 안에 흩어지면 빌드 화면과 어긋나기 시작한다.
 *
 * 진실은 트리 하나다. 편집기는 열 때 펼치고, 저장할 때 접는다.
 */
class EditableTimeline(
    val buildId: String,
    /** 편집 중인 덩어리 id. null = 레거시 평평 빌드(캔버스 아님). 저장 때 이 덩어리만 접는다. */
    val clumpId: String?,
    val recording: Recording?,
    val name: String,
    strokes: List<PlacedStroke>,
) {
    /** 편집 중인 궤적들. 시각 순으로 유지한다. */
    val strokes: MutableList<PlacedStroke> = strokes.toMutableList()

    val videoPath: String? get() = recording?.videoPath
    val videoOffsetMs: Long get() = recording?.videoOffsetMs ?: 0L

    /** 해당 시각의 화면 회전. 녹화 중 돌린 구간도 제대로 그린다. */
    fun rotationAt(tMs: Long): Int {
        val events = recording?.rotationEvents.orEmpty()
        if (events.isEmpty()) return 0
        var rot = events.first().rotation
        for (e in events) {
            if (e.tMs <= tMs) rot = e.rotation else break
        }
        return rot
    }

    val durationMs: Long
        get() = strokes.maxOfOrNull { it.startMs + it.stroke.durationMs } ?: 0L

    companion object {

        fun open(ctx: Context, build: Material): EditableTimeline {
            val target = targetClump(build)
            val recId = build.params.str("recordingId").ifEmpty { null }
            return EditableTimeline(
                buildId = build.id,
                clumpId = if (target === build) null else target.id,
                recording = recId?.let { RecordingStore.get(ctx, it) },
                name = build.meta.name,
                strokes = Timeline.flatten(ctx, target),
            )
        }

        /** 캔버스에서 시간축으로 열 덩어리를 고른다. 모자로 시작하는 첫 덩어리(=녹화본). 레거시면 빌드 자신. */
        private fun targetClump(build: Material): Material {
            val clumps = build.children.filter { it.typeId == "build" }
            if (clumps.isEmpty()) return build
            return clumps.firstOrNull { c ->
                c.children.firstOrNull()?.let { TypeKinds.kindOf(it.typeId) == Kind.HAT } == true
            } ?: clumps.first()
        }
    }

    /**
     * 편집 결과를 트리로 되돌린다.
     *
     * 궤적의 시각을 다시 순서와 대기로 접는다. 겹치는 것들은 parallel 로 묶이고, 갈래마다
     * 그룹 시작으로부터의 시차가 대기로 붙는다. 이 규칙은 [RecordingToTree] 와 같아야 하며,
     * 달라지면 녹화한 것과 편집한 것이 서로 다른 모양의 트리가 된다.
     */
    fun toTree(ctx: Context, original: Material): Material {
        val timed = strokes
            .sortedBy { it.startMs }
            .map { TimedStroke(it.startMs, it.stroke, it.strokeId) }

        // 궤적 본체가 바뀌었을 수 있으므로(자르기·이동) 저장소를 갱신한다.
        for (p in strokes) StrokeStore.update(ctx, p.strokeId, p.stroke)

        val rebuilt = RecordingToTree.build(ctx, timed, recording?.id, name)

        // 여는 덩어리만 새 스택으로 교체한다. 모자·위치·다른 덩어리는 그대로 둬 캔버스를 보존한다.
        // (예전엔 children 을 통째 덮어써 자유 배치·갈래가 사라졌다.)
        if (clumpId == null) return original.copy(children = rebuilt.children)
        return original.copy(children = original.children.map { clump ->
            if (clump.id != clumpId) clump
            else {
                val hat = clump.children.firstOrNull()?.takeIf { TypeKinds.kindOf(it.typeId) == Kind.HAT }
                clump.copy(children = (if (hat != null) listOf(hat) else emptyList()) + rebuilt.children)
            }
        })
    }
}
