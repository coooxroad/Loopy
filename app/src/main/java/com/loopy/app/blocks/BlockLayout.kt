package com.loopy.app.blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.loopy.app.core.material.Material
import kotlin.math.abs

/**
 * 캔버스 배치.
 *
 * 트리는 실행 순서를 알지만 어디에 그릴지는 모른다. 그 둘을 잇는 것이 이 파일의 일이다.
 *
 * 동시 실행의 갈래는 자기 부모를 [Meta.note] 에 담아 둔다. 별도 필드를 두지 않는 이유는,
 * 갈래도 결국 하나의 Material 이고 공리를 늘리지 않는 편이 낫기 때문이다.
 */

/** 저장된 좌표가 없으면 세로로 늘어놓는다. 자동 배치는 첫 인상만 책임진다. */
fun layoutStacks(build: Material): List<Material> {
    val out = ArrayList<Material>()
    var y = 40f

    for (child in build.children) {
        if (child.typeId == "parallel") {
            val node = if (hasPos(child)) child else child.copy(meta = child.meta.copy(x = 40f, y = y))
            out.add(node.copy(children = emptyList()))
            y += 150f

            var bx = -80f
            for (branch in child.children) {
                val b = if (hasPos(branch)) branch else branch.copy(meta = branch.meta.copy(x = bx, y = y))
                out.add(b.copy(meta = b.meta.copy(note = node.id)))
                bx += 230f
            }
            y += 120f
        } else {
            val placed = if (hasPos(child)) child else child.copy(meta = child.meta.copy(x = 40f, y = y))
            out.add(placed)
            y += 60f
        }
    }
    return out
}

private fun hasPos(m: Material) = m.meta.x != 0f || m.meta.y != 0f

/**
 * 캔버스를 다시 트리로.
 *
 * 화면에서 위에 있는 것이 먼저 실행된다. 갈래는 자기가 속한 동시 블록 안으로 돌아간다.
 */
fun flattenStacks(stacks: List<Material>): List<Material> {
    val forks = stacks.filter { it.meta.note.isNotEmpty() }
    val roots = stacks.filter { it.meta.note.isEmpty() }.sortedBy { it.meta.y }

    return roots.map { m ->
        if (m.typeId == "parallel") {
            m.copy(children = forks.filter { it.meta.note == m.id }.sortedBy { it.meta.x })
        } else {
            m
        }
    }
}

/**
 * 놓았을 때 붙이기.
 *
 * 앞 블록의 아래 볼록에 뒤 블록의 위 오목이 물린다. 겹치는 부분(노치 깊이)만큼 끌어올려야
 * 두 블록이 맞물린 것처럼 보인다. 그냥 아래에 붙이면 사이가 벌어져 이가 안 맞는다.
 *
 * 여러 개를 이어붙일 수 있어야 한다. 붙일 자리를 찾을 때 이미 무언가 붙어 있는 블록은
 * 건너뛰고, 체인의 맨 끝을 찾아간다.
 */
fun snapStacks(stacks: MutableList<Material>) {
    val snapX = 44f
    val snapY = 40f
    val overlap = 5f

    for (i in stacks.indices) {
        val m = stacks[i]
        if (m.meta.note.isNotEmpty()) continue
        if (specOf(m.typeId).shape == BlockShape.HAT) continue

        var best = -1
        var bestDist = Float.MAX_VALUE

        for (j in stacks.indices) {
            if (i == j) continue
            val other = stacks[j]
            if (other.meta.note.isNotEmpty()) continue
            if (specOf(other.typeId).shape == BlockShape.CAP) continue

            // 이미 아래에 무언가 붙어 있으면 그 자리는 찼다.
            val bottom = other.meta.y + blockHeight(other) - overlap
            val taken = stacks.any { k ->
                k.id != m.id && k.id != other.id &&
                    abs(k.meta.x - other.meta.x) < 4f && abs(k.meta.y - bottom) < 4f
            }
            if (taken) continue

            val dx = abs(m.meta.x - other.meta.x)
            val dy = abs(m.meta.y - bottom)
            if (dx < snapX && dy < snapY && dy < bestDist) {
                best = j
                bestDist = dy
            }
        }

        if (best >= 0) {
            val other = stacks[best]
            stacks[i] = m.copy(
                meta = m.meta.copy(
                    x = other.meta.x,
                    y = other.meta.y + blockHeight(other) - overlap,
                ),
            )
            return
        }
    }
}

/** 블록 하나의 높이. C블록은 안이 비어도 최소 높이를 갖는다. */
fun blockHeight(m: Material): Float = when (specOf(m.typeId).shape) {
    BlockShape.C_BLOCK -> 52f + 34f + 30f
    BlockShape.HAT -> 62f
    else -> 52f
}

/** 희미한 격자. 있는 줄도 모를 만큼 옅어야 하지만, 없으면 평면이 어디로 뻗는지 알 수 없다. */
fun DrawScope.drawGrid(color: Color, camera: Offset, zoom: Float) {
    val step = 32f * zoom
    if (step < 8f) return

    var x = camera.x % step
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = camera.y % step
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/** 동시 블록과 갈래를 잇는 선. 어느 갈래가 어디 속하는지 눈으로 보여야 한다. */
fun DrawScope.drawForkLinks(
    stacks: List<Material>,
    color: Color,
    camera: Offset,
    zoom: Float,
) {
    val forks = stacks.filter { it.meta.note.isNotEmpty() }
    for (f in forks) {
        val parent = stacks.firstOrNull { it.id == f.meta.note } ?: continue

        val from = Offset(
            (parent.meta.x + 100f) * zoom + camera.x,
            (parent.meta.y + 52f) * zoom + camera.y,
        )
        val to = Offset(
            (f.meta.x + 100f) * zoom + camera.x,
            f.meta.y * zoom + camera.y,
        )

        // 곧은 직선은 어느 갈래가 어디로 가는지 헷갈린다. 부드럽게 휘면 눈이 따라가기 쉽다.
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(from.x, from.y)
            cubicTo(
                from.x, from.y + (to.y - from.y) * 0.5f,
                to.x, from.y + (to.y - from.y) * 0.5f,
                to.x, to.y,
            )
        }
        drawPath(
            path,
            color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 7f * zoom,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
    }
}
