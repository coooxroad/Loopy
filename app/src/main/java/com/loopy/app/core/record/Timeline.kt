package com.loopy.app.core.record

import android.content.Context
import com.loopy.app.core.material.Material

/**
 * 트리 ↔ 시간축 변환.
 *
 * 빌드 화면은 순서축으로, 편집기는 시간축으로 같은 트리를 본다. 두 화면이 서로 다른 데이터를
 * 들고 있으면 한쪽에서 고친 것이 다른 쪽에 반영되지 않는다. 그래서 진실은 트리 하나로 두고,
 * 편집기가 열 때 시각을 계산하고 닫을 때 다시 순서로 되돌린다.
 *
 * 계산 규칙은 [RecordingToTree] 의 역이다.
 *  - 대기 블록만큼 커서를 민다.
 *  - 터치는 커서 위치에서 시작하고, 궤적 길이만큼 커서를 민다.
 *  - parallel 은 모든 갈래가 같은 시각에 시작하고, 가장 늦게 끝나는 갈래가 커서를 정한다.
 */
object Timeline {

    /** 트리를 펼쳐 각 궤적이 언제 시작하는지 계산한다. */
    fun flatten(ctx: Context, build: Material): List<PlacedStroke> {
        val out = ArrayList<PlacedStroke>()
        walk(ctx, build.children, 0L, out)
        return out.sortedBy { it.startMs }
    }

    /** @return 이 블록들을 모두 실행하는 데 걸리는 시간 */
    private fun walk(
        ctx: Context,
        blocks: List<Material>,
        from: Long,
        out: MutableList<PlacedStroke>,
    ): Long {
        var cursor = from
        for (b in blocks) {
            if (!b.enabled) continue
            when (b.typeId) {
                "wait" -> cursor += b.params.long("ms")

                "touch" -> {
                    val id = b.params.str("strokeId")
                    val stroke = StrokeStore.get(ctx, id)
                    if (stroke != null) {
                        out.add(PlacedStroke(b.id, id, cursor, stroke))
                        cursor += stroke.durationMs
                    }
                }

                "parallel" -> {
                    // 갈래들은 같은 시각에 출발한다. 끝나는 시각은 제각각이고,
                    // 가장 늦게 끝나는 갈래가 다음 블록의 시작을 정한다.
                    var latest = cursor
                    for (branch in b.children) {
                        val end = walk(ctx, listOf(branch), cursor, out)
                        latest = maxOf(latest, end)
                    }
                    cursor = latest
                }

                // 갈래를 감싼 작은 순서 컨테이너이거나, 중첩된 빌드.
                "build" -> cursor = walk(ctx, b.children, cursor, out)

                // 조건·반복은 시각을 확정할 수 없다. 편집기는 이런 빌드를 시간축으로 열지 않는다.
                else -> Unit
            }
        }
        return cursor
    }

    /**
     * 이 빌드를 시간축으로 열 수 있는가.
     *
     * 조건이나 반복이 들어 있으면 재생할 때마다 타이밍이 달라진다. 그런 트리를 억지로
     * 타임라인에 그리면 화면에 보이는 것과 실제 실행이 어긋난다. 그럴 땐 빌드 화면으로 연다.
     */
    fun isLinear(build: Material): Boolean = build.children.all { linear(it) }

    private fun linear(m: Material): Boolean = when (m.typeId) {
        "wait", "touch" -> true
        "parallel", "build" -> m.children.all { linear(it) }
        else -> false
    }
}

/**
 * 시각이 정해진 궤적. 편집기가 다루는 단위.
 *
 * added = 편집기에서 추가로 촬영한 것. 원래 녹화된 궤적과 색으로 구분해 보여준다.
 * 무엇을 나중에 덧붙였는지 눈으로 알 수 있어야 되돌리기 쉽다.
 */
data class PlacedStroke(
    /** 이 궤적을 재생하는 touch Material 의 id. 편집 결과를 트리에 되돌릴 때 쓴다. */
    val materialId: String,
    val strokeId: String,
    val startMs: Long,
    val stroke: Stroke,
    val added: Boolean = false,
)
