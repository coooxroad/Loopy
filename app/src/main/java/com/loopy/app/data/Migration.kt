package com.loopy.app.data

import android.content.Context
import com.loopy.app.core.material.Material
import com.loopy.app.core.record.Recording
import com.loopy.app.core.record.RecordingStore
import com.loopy.app.core.record.RecordingToTree
import com.loopy.app.core.record.RotationEvent
import com.loopy.app.core.record.Stroke
import com.loopy.app.core.record.TimedStroke
import com.loopy.app.core.record.TouchSample
import com.loopy.app.macro.MacroStore

/**
 * 구버전 데이터 이주.
 *
 * 예전 구조는 매크로(스트로크 묶음)와 플레이리스트(매크로 나열)가 서로 다른 개념이었다.
 * 새 구조에서는 둘 다 Material 이고, 플레이리스트는 그저 자식이 촬영 매크로뿐인 빌드다.
 * 개념이 하나로 합쳐지므로 플레이리스트라는 이름은 사라진다.
 *
 * 스트로크와 영상 파일은 그대로 두고 참조만 옮긴다. 사용자가 녹화한 것을 다시 만들 수는 없다.
 */
object Migration {

    fun fromLegacy(ctx: Context): List<Material> {
        val out = ArrayList<Material>()

        val macros = runCatching { MacroStore.list(ctx) }.getOrDefault(emptyList())
        for (m in macros) {
            // 옛 매크로는 궤적이 시작 시각을 들고 있었다. 새 구조에서는 시각이 순서와 대기가 되므로
            // 트리로 변환한다. 영상과 회전 이력은 녹화 리소스로 옮긴다.
            val recId = RecordingStore.newId()
            RecordingStore.put(
                ctx,
                Recording(
                    id = recId,
                    videoPath = m.videoPath,
                    videoOffsetMs = m.videoOffsetMs,
                    rotationEvents = m.rotationEvents.map { RotationEvent(it.tMs, it.rotation) },
                ),
            )
            val timed = m.strokes.map { s ->
                TimedStroke(
                    startMs = s.startMs,
                    stroke = Stroke(
                        durationMs = s.durationMs,
                        samples = s.samples.map { TouchSample(it.t, it.nx, it.ny) },
                        rotation = s.rotation,
                    ),
                )
            }
            out.add(RecordingToTree.build(ctx, timed, recId, m.name))
        }


        return out
    }
}
