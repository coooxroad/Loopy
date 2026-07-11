package com.loopy.app.data

import android.content.Context
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.LoopParams
import com.loopy.app.core.material.NoParams
import com.loopy.app.core.material.TouchParams
import com.loopy.app.core.material.WaitParams
import com.loopy.app.macro.MacroStore
import com.loopy.app.macro.PlaylistStore

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
            out.add(
                Material(
                    id = MaterialStore.newId(),
                    typeId = "touch",
                    params = TouchParams(m.id),
                    meta = Meta(name = m.name, createdAt = m.createdAt),
                ),
            )
        }

        val playlists = runCatching { PlaylistStore.list(ctx) }.getOrDefault(emptyList())
        for (p in playlists) {
            val body = ArrayList<Material>()
            for ((i, macroId) in p.macroIds.withIndex()) {
                val macro = macros.firstOrNull { it.id == macroId } ?: continue
                body.add(
                    Material(
                        id = MaterialStore.newId(),
                        typeId = "touch",
                        params = TouchParams(macroId),
                        meta = Meta(name = macro.name, createdAt = macro.createdAt),
                    ),
                )
                // 매크로 사이 간격은 새 구조에서 대기 블록이다. 별도 설정 항목이 필요 없어진다.
                if (p.gapMs > 0 && i < p.macroIds.lastIndex) {
                    body.add(
                        Material(
                            id = MaterialStore.newId(),
                            typeId = "wait",
                            params = WaitParams(p.gapMs.toLong()),
                        ),
                    )
                }
            }

            // 반복 횟수도 마찬가지로 반복 블록이 된다.
            val children = if (p.cycles > 1) {
                listOf(
                    Material(
                        id = MaterialStore.newId(),
                        typeId = "loop",
                        params = LoopParams(count = p.cycles),
                        children = body,
                    ),
                )
            } else {
                body
            }

            out.add(
                Material(
                    id = MaterialStore.newId(),
                    typeId = "build",
                    params = NoParams,
                    children = children,
                    meta = Meta(name = p.name, createdAt = p.createdAt),
                ),
            )
        }

        return out
    }
}
