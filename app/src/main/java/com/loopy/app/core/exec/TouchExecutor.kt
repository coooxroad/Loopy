package com.loopy.app.core.exec

import android.content.Context
import com.loopy.app.core.material.Material
import com.loopy.app.core.record.StrokeStore

/**
 * 궤적 하나를 재생한다.
 *
 * 궤적이 끝날 때까지 돌아오지 않는다. 그래야 다음 블록이 제 시각에 시작하고,
 * 트리의 순서가 곧 실제 타이밍이 된다.
 *
 * 회전은 스트로크가 캡처될 때의 값을 쓴다. 녹화 중 기기를 돌린 구간도 그대로 재현된다.
 */
class TouchExecutor(private val context: Context) : Executor {

    override val typeId = "touch"

    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val id = material.params.str("strokeId")
        val stroke = StrokeStore.get(context, id)
        if (stroke == null) {
            ctx.log.add(material.id, "궤적을 찾을 수 없음: $id")
            return Flow.Next
        }

        ctx.cancel.throwIfCancelled()
        ctx.io.playStroke(stroke)
        return Flow.Next
    }
}
