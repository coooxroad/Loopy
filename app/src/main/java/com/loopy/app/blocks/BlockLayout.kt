package com.loopy.app.blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.loopy.app.core.material.Material
import kotlin.math.abs

/**
 * 노치(요철) 깊이. 블록이 맞물리는 유일한 치수 기준.
 *
 * [BlockDraw] 가 이 값으로 볼록/오목을 그리고, 스냅이 이 값으로 겹침을 계산한다. 한 곳에서만
 * 정의해야 "그려지는 모양"과 "붙는 위치"가 어긋나지 않는다.
 */
const val NOTCH_DEPTH = 5f

/**
 * 캔버스 배치.
 *
 * 트리는 실행 순서를 알지만 어디에 그릴지는 모른다. 그 둘을 잇는 것이 이 파일의 일이다.
 *
 * 동시 실행의 갈래는 자기 부모를 [Meta.note] 에 담아 둔다. 별도 필드를 두지 않는 이유는,
 * 갈래도 결국 하나의 Material 이고 공리를 늘리지 않는 편이 낫기 때문이다.
 */

/**
 * 트리를 캔버스에 배치한다.
 *
 * 열 때마다 **깔끔히 물린 세로 줄기로 다시 정렬**한다. 예전엔 저장된 좌표가 있으면 그대로
 * 썼는데(hasPos), 옛 버전에서 겹쳐 저장된 좌표를 계속 물고 와 촬영 매크로가 뭉개져 보였다.
 * 순서(실행 순서)는 children 순서로 보존되므로, 좌표를 다시 흘려도 논리는 그대로다.
 *
 * 동시 블록은 본체 줄기에 남고, 갈래만 오른쪽에 떠서 선으로 이어진다. 갈래를 줄기 사이에
 * 끼우면 세로 흐름이 끊기기 때문이다.
 */
fun layoutStacks(build: Material): List<Material> {
    val out = ArrayList<Material>()
    val x = 40f
    var y = 40f

    for (child in build.children) {
        if (child.typeId == "parallel") {
            val node = child.copy(children = emptyList(), meta = child.meta.copy(x = x, y = y))
            out.add(node)

            // 갈래는 줄기 오른쪽에 위에서 아래로. 몇 개든 늘어도 본체 흐름을 막지 않는다.
            var by = y
            for (branch in child.children) {
                out.add(branch.copy(meta = branch.meta.copy(x = x + 280f, y = by, note = node.id)))
                by += blockHeight(branch) + 20f
            }

            // 다음 블록은 동시 블록 바로 아래에 물린다(갈래 아래가 아니라).
            y += meshStep(node)
        } else {
            val placed = child.copy(meta = child.meta.copy(x = x, y = y))
            out.add(placed)
            y += meshStep(placed)
        }
    }
    return out
}

/**
 * 다음 블록이 앞 블록에 물리도록 내려갈 거리.
 *
 * 높이에서 노치 두 겹을 빼면 아래 볼록이 위 오목에 딱 들어간다. 스냅(snapStacks)도 같은
 * 계산을 써야 열었을 때와 끌어 붙였을 때가 어긋나지 않는다.
 */
fun meshStep(m: Material): Float = blockHeight(m) - NOTCH_DEPTH * 2f

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
            val bottom = other.meta.y + meshStep(other)
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
                    y = other.meta.y + meshStep(other),
                ),
            )
            return
        }
    }
}

/**
 * 블록 높이(dp) — 단일 출처.
 *
 * 스냅 계산과 실제 렌더가 같은 값을 봐야 블록이 맞물린다. 예전엔 스냅은 여기서, 렌더는
 * BlockView 에서 각자 계산해 C블록에서 어긋났다(스냅 116 vs 렌더 112~). 이제 두 쪽이
 * 모두 이 함수를 파생해 쓴다.
 */
fun blockHeightDp(m: Material): Dp = when (specOf(m.typeId).shape) {
    BlockShape.C_BLOCK -> 52.dp + innerHeightDp(m) + 30.dp
    BlockShape.HAT -> 62.dp
    else -> 52.dp
}

/** C블록이 자식을 품는 홈의 높이. 자식 수에 따라 늘고, 비어도 최소치를 갖는다. */
fun innerHeightDp(m: Material): Dp =
    if (specOf(m.typeId).shape == BlockShape.C_BLOCK) {
        (m.children.size * 52).dp.coerceAtLeast(30.dp)
    } else {
        0.dp
    }

/** 스냅 계산용 높이(dp 값). 렌더 높이와 반드시 같다. */
fun blockHeight(m: Material): Float = blockHeightDp(m).value

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

        // 부모(동시 블록) 아래 가운데에서 시작한다. 높이를 하드코딩(52)하지 않고 실제 높이를
        // 쓴다. 부모가 C블록이거나 높이가 다르면 하드코딩 값은 선이 블록 중간에서 튀어나온다.
        val from = Offset(
            (parent.meta.x + 100f) * zoom + camera.x,
            (parent.meta.y + blockHeight(parent)) * zoom + camera.y,
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
