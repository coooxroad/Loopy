package com.loopy.app.blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.loopy.app.core.material.BuildParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.NoParams
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.min

/**
 * 블록 배치 엔진 — 구조 우선, 자유 배치.
 *
 * 캔버스 = build 하나. 그 자식 = **덩어리(build)들**. 각 덩어리는 meta.x/y 로 캔버스 위 자기 자리를
 * 갖는다. 덩어리의 자식 = 블록 스택. 스택 안쪽은 좌표를 저장하지 않고 트리에서 계산한다 —
 * 그래서 블록 N+1 은 언제나 N 의 아래 연결점에 딱 놓이고 맞물림이 어긋날 수 없다.
 *
 * 정리하면: 덩어리끼리는 자유 배치(각자 x/y), 덩어리 안쪽은 구조 우선(맞물림).
 * 실행은 모자로 시작하는 덩어리만 (진입점에서 처리).
 *
 * 이 파일은 순수 계산만: 캔버스 → 놓인 좌표들(Placed) + 끼울 수 있는 실제 연결점들(Slot).
 * 트리 변형(떼기/끼우기/추가/수정/삭제)도 여기 모아 둔다. 화면·제스처는 BlockCanvas 가 맡는다.
 */

const val NOTCH_DEPTH = 5f      // 요철 깊이. BlockDraw 의 nd 와 같아야 한다.
const val ROW = 52f             // 보통 블록 한 칸 높이
const val HAT_H = 62f           // 모자 블록 높이
const val C_HEADER = 52f        // C블록 머리
const val C_FOOT = 22f          // C블록 발
const val INDENT = 22f          // C블록 안쪽 들여쓰기
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

/**
 * 블록을 끼울 수 있는 실제 연결점.
 * clumpId = 이 자리가 속한 덩어리. parentId=null 이면 덩어리 최상위 줄기, 아니면 C블록 입.
 * 빈 공간에는 슬롯을 만들지 않는다 — 유령 슬롯 금지.
 */
data class Slot(
    val clumpId: String,
    val parentId: String?,
    val index: Int,
    val x: Float,
    val y: Float,
)

class Layout {
    val placed = ArrayList<Placed>()
    val slots = ArrayList<Slot>()
}

/** 한 덩어리의 스택을 좌표로 편다. @return 이 스택이 차지한 높이 */
private fun layoutStack(
    clumpId: String,
    parentId: String?,
    children: List<Material>,
    x: Float,
    startY: Float,
    depth: Int,
    out: Layout,
): Float {
    var y = startY
    for ((i, c) in children.withIndex()) {
        // c 앞에 끼우는 자리. 단, 모자 위에는 아무것도 못 붙이므로 모자 앞 자리는 만들지 않는다.
        if (!(i == 0 && isHat(c))) out.slots.add(Slot(clumpId, parentId, i, x, y))
        out.placed.add(Placed(c, x, y, depth))

        if (isC(c)) {
            layoutStack(clumpId, c.id, c.children, x + INDENT, y + C_HEADER, depth + 1, out)
        }
        // parallel(동시) 는 지금은 평범한 블록으로 둔다. 노드+갈래 UI 는 다음 업데이트에서 복원.
        y += meshStep(c)
    }
    // 맨 끝(마지막 블록 아래)에 붙이는 자리
    out.slots.add(Slot(clumpId, parentId, children.size, x, y))
    return y - startY
}

/** 캔버스(덩어리들)를 통째로 편다. 각 덩어리는 자기 meta.x/y 에서 시작한다. */
fun layoutCanvas(canvas: Material): Layout {
    val out = Layout()
    for (clump in canvas.children) {
        layoutStack(clump.id, null, clump.children, clump.meta.x, clump.meta.y, 0, out)
    }
    return out
}

/**
 * 드래그 지점에서 가장 가까운 연결점. **좁게** 잡는다 — 스냅은 조립 편의 기능일 뿐,
 * 기본은 자유 배치다. 가까이 갔을 때만 자석이 걸린다.
 */
/**
 * 위·아래 두 기준점으로 가장 가까운 연결점을 찾는다. 끌고 온 블록의 윗변(아래로 붙이기)과
 * 아랫변(다른 블록 위로 얹기) 둘 다 보므로, 위에 얹는 조립도 잡힌다.
 */
fun nearestSlot2(slots: List<Slot>, x1: Float, y1: Float, x2: Float, y2: Float, radius: Float = 20f): Slot? {
    var best: Slot? = null
    var bestD = radius
    for (s in slots) {
        val d = min(hypot(s.x - x1, s.y - y1), hypot(s.x - x2, s.y - y2))
        if (d < bestD) { bestD = d; best = s }
    }
    return best
}

fun nearestSlot(slots: List<Slot>, x: Float, y: Float, radius: Float = 20f): Slot? {
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

// ---- 덩어리/블록 만들기 ----

private fun freshHat(): Material =
    Material(UUID.randomUUID().toString(), "trigger.manual", NoParams)

/** 위치를 가진 새 덩어리(build). */
fun newClump(children: List<Material>, x: Float, y: Float): Material =
    Material(UUID.randomUUID().toString(), "build", BuildParams(null), children, Meta(x = x, y = y))

/**
 * 레거시 빌드(자식=블록 스택)를 캔버스 모양(덩어리들)으로 바꾼다. 멱등:
 * 이미 캔버스(자식이 전부 build)면 그대로 둔다. 녹화가 만든 빌드는 자식이 대기/터치라 여기서 감싸진다.
 * 감쌀 때 맨 위에 모자를 얹어 "이게 실행되는 덩어리"임을 표시한다(id·이름은 캔버스가 유지).
 */
fun migrate(build: Material): Material {
    val kids = build.children
    val alreadyCanvas = kids.isNotEmpty() && kids.all { it.typeId == "build" }
    if (alreadyCanvas) return build.copy(children = kids.map { dedupeHats(it) })
    val stack = if (kids.firstOrNull()?.let { isHat(it) } == true) kids else listOf(freshHat()) + kids
    return build.copy(children = listOf(dedupeHats(newClump(stack, 24f, 24f))))
}

/** 덩어리 맨 앞 모자는 최대 1개만 남긴다. 중복 모자(실행하면 두 개)를 정리한다. */
private fun dedupeHats(clump: Material): Material {
    val leadingHats = clump.children.takeWhile { isHat(it) }
    if (leadingHats.size <= 1) return clump
    val rest = clump.children.dropWhile { isHat(it) }
    return clump.copy(children = listOf(leadingHats.first()) + rest)
}

// ---- 캔버스 트리 변형 (전부 불변 복사) ----

/** 한 덩어리 안에서 [id] 와 그 아래(같은 스택의 뒤 형제)를 떼어낸다. (남은 덩어리, 딸려온 블록들) */
private fun detachFrom(root: Material, id: String): Pair<Material, List<Material>> {
    val idx = root.children.indexOfFirst { it.id == id }
    if (idx >= 0) {
        val tail = root.children.subList(idx, root.children.size).toList()
        val kept = root.copy(children = root.children.subList(0, idx).toList())
        return kept to tail
    }
    val newKids = ArrayList<Material>(root.children.size)
    var found: List<Material> = emptyList()
    for (child in root.children) {
        if (found.isEmpty()) {
            val (nc, tail) = detachFrom(child, id)
            newKids.add(nc)
            if (tail.isNotEmpty()) found = tail
        } else newKids.add(child)
    }
    return root.copy(children = newKids) to found
}

/**
 * 블록 [id] 와 그 아래를 캔버스에서 떼어낸다. 떼고 나서 빈 덩어리는 캔버스에서 사라진다.
 * @return (뗀 뒤의 캔버스, 딸려온 블록들)
 */
fun detachTail(canvas: Material, id: String): Pair<Material, List<Material>> {
    val newClumps = ArrayList<Material>(canvas.children.size)
    var tail: List<Material> = emptyList()
    for (clump in canvas.children) {
        if (tail.isEmpty() && findBlock(clump, id) != null) {
            val (kept, t) = detachFrom(clump, id)
            tail = t
            if (kept.children.isNotEmpty()) newClumps.add(kept)   // 빈 덩어리는 버린다
        } else newClumps.add(clump)
    }
    return canvas.copy(children = newClumps) to tail
}

/** [slot] 이 가리키는 덩어리의 자리에 blocks 를 끼운다. */
fun insertAtSlot(canvas: Material, slot: Slot, blocks: List<Material>): Material {
    val newClumps = canvas.children.map { clump ->
        if (clump.id == slot.clumpId) insertInto(clump, slot.parentId, slot.index, blocks)
        else clump
    }
    return canvas.copy(children = newClumps)
}

/** blocks 를 담은 새 덩어리를 (x,y) 에 추가한다. 자유 배치 드롭에 쓴다. */
fun addClump(canvas: Material, blocks: List<Material>, x: Float, y: Float): Material =
    canvas.copy(children = canvas.children + newClump(blocks, x, y))

/** parallel 등 [parentId] 블록의 자식 끝에 block 을 붙인다(갈래 추가 등). */
fun addChild(canvas: Material, parentId: String, block: Material): Material =
    canvas.copy(children = canvas.children.map { insertInto(it, parentId, Int.MAX_VALUE, listOf(block)) })

/** 덩어리의 위치를 옮긴다(스택 전체를 통째로 잡아 옮길 때). */
fun moveClump(canvas: Material, clumpId: String, x: Float, y: Float): Material =
    canvas.copy(children = canvas.children.map {
        if (it.id == clumpId) it.copy(meta = it.meta.copy(x = x, y = y)) else it
    })

/** [parentId] (null=덩어리 최상위) 의 자식 [index] 자리에 blocks 를 끼운다(덩어리 내부). */
private fun insertInto(root: Material, parentId: String?, index: Int, blocks: List<Material>): Material {
    if (parentId == null || parentId == root.id) {
        val kids = ArrayList(root.children)
        val at = index.coerceIn(0, kids.size)
        kids.addAll(at, blocks)
        return root.copy(children = kids)
    }
    return root.copy(children = root.children.map { insertInto(it, parentId, index, blocks) })
}

/** id 블록을 새 값으로 교체(파라미터 편집). 캔버스 전체를 훑는다. */
fun updateBlock(root: Material, block: Material): Material {
    val kids = root.children.map { if (it.id == block.id) block else updateBlock(it, block) }
    return root.copy(children = kids)
}

/** id 블록과 그 하위를 삭제. 뒤 형제는 남고, 빈 덩어리는 사라진다. */
fun removeBlock(canvas: Material, id: String): Material {
    val kids = canvas.children
        .map { clump -> removeInside(clump, id) }
        .filter { it.children.isNotEmpty() }
    return canvas.copy(children = kids)
}

private fun removeInside(root: Material, id: String): Material {
    val kids = root.children.filter { it.id != id }.map { removeInside(it, id) }
    return root.copy(children = kids)
}

/** 트리에서 id 로 블록 찾기. */
fun findBlock(root: Material, id: String): Material? {
    if (root.id == id) return root
    for (c in root.children) findBlock(c, id)?.let { return it }
    return null
}

/** [id] 블록과 그 아래 형제들(딸려올 그룹)을 그대로 찾아 반환. */
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

// ---- 배경 ----

fun DrawScope.drawGrid(color: Color, offsetX: Float, offsetY: Float) {
    val gap = 74f
    var gx = offsetX % gap
    if (gx < 0) gx += gap
    while (gx < size.width) {
        drawLine(color, Offset(gx, 0f), Offset(gx, size.height), 1f)
        gx += gap
    }
    var gy = offsetY % gap
    if (gy < 0) gy += gap
    while (gy < size.height) {
        drawLine(color, Offset(0f, gy), Offset(size.width, gy), 1f)
        gy += gap
    }
}
