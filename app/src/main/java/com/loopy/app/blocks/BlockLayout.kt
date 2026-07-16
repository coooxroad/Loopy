package com.loopy.app.blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.loopy.app.core.material.Material
import kotlin.math.hypot

/**
 * 블록 배치 엔진 — 구조 우선.
 *
 * 스크래치의 핵심: 블록은 자기 좌표를 갖지 않는다. 좌표는 **트리에서 계산**된다. 그래서
 * 블록 N+1 은 언제나 블록 N 의 아래 연결점에 정확히 놓이고, 맞물림이 어긋날 수가 없다.
 * (예전엔 블록마다 x,y 를 저장하고 픽셀 거리로 연결을 추측해서, 조금만 틀어져도 홈이 안 맞았다.)
 *
 * 이 파일은 순수 계산만 한다: 트리 → 놓인 좌표들(Placed) + 끼울 수 있는 자리들(Slot).
 * 트리 변형(떼기/끼우기/수정/삭제)도 여기 모아 둔다. 화면·제스처는 BlockCanvas 가 맡는다.
 */

const val NOTCH_DEPTH = 5f      // 요철 깊이. BlockDraw 의 nd 와 같아야 한다.
const val ROW = 52f             // 보통 블록 한 칸 높이
const val HAT_H = 62f           // 모자 블록 높이
const val C_HEADER = 52f        // C블록 머리
const val C_FOOT = 22f          // C블록 발
const val INDENT = 22f          // C블록 안쪽 들여쓰기
const val COL_W = 250f          // 동시 갈래 컬럼 간격
const val MOUTH_MIN = 34f       // 빈 C블록 입 최소 높이

fun isC(m: Material): Boolean = specOf(m.typeId).shape == BlockShape.C_BLOCK
fun isHat(m: Material): Boolean = specOf(m.typeId).shape == BlockShape.HAT

/** 위 블록에서 아래 블록으로 내려갈 거리. 노치 두 겹만큼 겹쳐야 볼록이 오목에 딱 든다. */
fun meshStep(m: Material): Float = blockHeight(m) - NOTCH_DEPTH * 2f

/** 자식 스택이 차지하는 높이(맞물린 상태). 비어 있으면 최소치. */
fun stackHeight(children: List<Material>): Float {
    if (children.isEmpty()) return MOUTH_MIN
    var h = 0f
    for (c in children) h += meshStep(c)
    return h + NOTCH_DEPTH * 2f
}

/** 블록 한 칸의 높이. C블록은 자식에 따라 커진다. */
fun blockHeight(m: Material): Float = when {
    isC(m) -> C_HEADER + stackHeight(m.children) + C_FOOT
    isHat(m) -> HAT_H
    else -> ROW
}

/** C블록 입(자식이 들어가는 홈)의 높이. 렌더가 참조한다. */
fun innerHeight(m: Material): Float = if (isC(m)) stackHeight(m.children) else 0f

/** 화면에 놓인 블록 하나. */
data class Placed(
    val block: Material,
    val x: Float,
    val y: Float,
    val depth: Int,
)

/** 블록을 끼울 수 있는 자리. parentId=null 이면 최상위 줄기. */
data class Slot(
    val parentId: String?,
    val index: Int,
    val x: Float,
    val y: Float,
)

class Layout {
    val placed = ArrayList<Placed>()
    val slots = ArrayList<Slot>()
}

/**
 * 트리를 좌표로 편다.
 *
 * @return 이 스택이 차지한 높이
 */
fun layoutScript(
    parentId: String?,
    children: List<Material>,
    x: Float,
    startY: Float,
    depth: Int,
    out: Layout,
): Float {
    var y = startY
    for ((i, c) in children.withIndex()) {
        out.slots.add(Slot(parentId, i, x, y))          // c 앞에 끼우는 자리
        out.placed.add(Placed(c, x, y, depth))

        if (isC(c)) {
            layoutScript(c.id, c.children, x + INDENT, y + C_HEADER, depth + 1, out)
        } else if (c.typeId == "parallel") {
            // 갈래는 오른쪽 컬럼으로. 각 갈래는 자체 스택.
            var bx = x + COL_W
            for (branch in c.children) {
                out.placed.add(Placed(branch, bx, y, depth + 1))
                if (isC(branch)) {
                    layoutScript(branch.id, branch.children, bx + INDENT, y + C_HEADER, depth + 2, out)
                }
                bx += COL_W
            }
        }
        y += meshStep(c)
    }
    out.slots.add(Slot(parentId, children.size, x, y))  // 맨 끝에 붙이는 자리
    return y - startY
}

fun layoutRoot(root: Material, originX: Float, originY: Float): Layout {
    val out = Layout()
    layoutScript(null, root.children, originX, originY, 0, out)
    return out
}

/** 드래그 지점에서 가장 가까운 자리. 자석처럼 넉넉히 잡는다. */
fun nearestSlot(slots: List<Slot>, x: Float, y: Float, radius: Float = 60f): Slot? {
    var best: Slot? = null
    var bestD = radius
    for (s in slots) {
        val d = hypot(s.x - x, s.y - y)
        if (d < bestD) {
            bestD = d
            best = s
        }
    }
    return best
}

// ---- 트리 변형 (전부 불변 복사) ----

/**
 * 블록 [id] 와 그 아래(같은 스택의 뒤 형제들)를 떼어낸다.
 *
 * 스크래치식: 스택 중간 블록을 잡으면 그 블록과 아래가 함께 딸려오고, 위는 남는다.
 * @return (뗀 뒤의 트리, 딸려온 블록들). 못 찾으면 (원본, 빈 리스트).
 */
fun detachFrom(root: Material, id: String): Pair<Material, List<Material>> {
    val idx = root.children.indexOfFirst { it.id == id }
    if (idx >= 0) {
        val tail = root.children.subList(idx, root.children.size).toList()
        val kept = root.copy(children = root.children.subList(0, idx).toList())
        return kept to tail
    }
    // 자식들 속을 뒤진다.
    val newKids = ArrayList<Material>(root.children.size)
    var found: List<Material> = emptyList()
    for (child in root.children) {
        if (found.isEmpty()) {
            val (nc, tail) = detachFrom(child, id)
            newKids.add(nc)
            if (tail.isNotEmpty()) found = tail
        } else {
            newKids.add(child)
        }
    }
    return root.copy(children = newKids) to found
}

/** [parentId] (null=최상위) 의 자식 [index] 자리에 blocks 를 끼운다. */
fun insertInto(root: Material, parentId: String?, index: Int, blocks: List<Material>): Material {
    if (parentId == null || parentId == root.id) {
        val kids = ArrayList(root.children)
        val at = index.coerceIn(0, kids.size)
        kids.addAll(at, blocks)
        return root.copy(children = kids)
    }
    return root.copy(children = root.children.map { insertInto(it, parentId, index, blocks) })
}

/** id 블록을 새 값으로 교체(파라미터 편집). */
fun updateBlock(root: Material, block: Material): Material {
    val kids = root.children.map { if (it.id == block.id) block else updateBlock(it, block) }
    return root.copy(children = kids)
}

/** id 블록과 그 하위를 삭제. 뒤 형제는 남는다. */
fun removeBlock(root: Material, id: String): Material {
    val kids = root.children.filter { it.id != id }.map { removeBlock(it, id) }
    return root.copy(children = kids)
}

/** 트리에서 id 로 블록 찾기. */
fun findBlock(root: Material, id: String): Material? {
    if (root.id == id) return root
    for (c in root.children) findBlock(c, id)?.let { return it }
    return null
}

// ---- 배경 ----

fun DrawScope.drawGrid(color: Color, camera: Offset, zoom: Float) {
    val gap = 28f * zoom
    if (gap < 6f) return
    var gx = camera.x % gap
    while (gx < size.width) {
        drawLine(color, Offset(gx, 0f), Offset(gx, size.height), 1f)
        gx += gap
    }
    var gy = camera.y % gap
    while (gy < size.height) {
        drawLine(color, Offset(0f, gy), Offset(size.width, gy), 1f)
        gy += gap
    }
}

/** [id] 블록과 그 아래 형제들(딸려올 그룹)을 복사 없이 그대로 찾아 반환. */
fun tailOf(root: Material, id: String): List<Material> {
    val idx = root.children.indexOfFirst { it.id == id }
    if (idx >= 0) return root.children.subList(idx, root.children.size).toList()
    for (c in root.children) {
        val t = tailOf(c, id)
        if (t.isNotEmpty()) return t
    }
    return emptyList()
}

/** blocks 와 그 하위 전부의 id 집합. 자기 안에 드롭하는 걸 막을 때 쓴다. */
fun allIds(blocks: List<Material>): Set<String> {
    val s = HashSet<String>()
    fun rec(m: Material) { s.add(m.id); m.children.forEach(::rec) }
    blocks.forEach(::rec)
    return s
}
