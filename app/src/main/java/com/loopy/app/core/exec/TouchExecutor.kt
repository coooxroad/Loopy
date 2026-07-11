package com.loopy.app.core.exec

import android.content.Context
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.TouchParams
import com.loopy.app.macro.MacroStore

/**
 * 촬영 매크로 실행기.
 *
 * Material 은 매크로를 id 로만 참조하므로, 실행 시점에 본체(스트로크·회전 이력)를 읽어
 * [com.loopy.app.core.io.Io] 로 넘긴다. 회전 이력이 있으면 스트로크마다 그 시점의
 * 회전으로 좌표를 풀어야 녹화 중 기기를 돌린 구간도 제대로 재생된다.
 *
 * 저장소를 실행기가 직접 아는 것이 이상적이지는 않지만, 매크로 본체는 무거워서
 * Material 안에 담을 수 없다. 참조를 푸는 지점이 어딘가에는 있어야 한다.
 */
class TouchExecutor(private val context: Context) : Executor {

    override val typeId = "touch"

    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val macroId = (material.params as TouchParams).macroId
        val macro = MacroStore.read(context, macroId)
        if (macro == null) {
            ctx.log.add(material.id, "매크로를 찾을 수 없음: $macroId")
            return Flow.Next
        }

        ctx.cancel.throwIfCancelled()
        ctx.io.playStrokes(macro.strokes) { tMs -> rotationAt(macro, tMs) }
        return Flow.Next
    }

    private fun rotationAt(macro: com.loopy.app.macro.Macro, tMs: Long): Int {
        val events = macro.rotationEvents
        if (events.isEmpty()) return macro.rotation
        var rot = events.first().rotation
        for (e in events) {
            if (e.tMs <= tMs) rot = e.rotation else break
        }
        return rot
    }
}
