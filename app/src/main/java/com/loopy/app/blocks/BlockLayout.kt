package com.loopy.app.blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.loopy.app.core.material.Clump
import com.loopy.app.core.material.Kind
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.ParamBag
import com.loopy.app.core.material.Meta
import java.util.UUID
import kotlin.math.hypot

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

/** 모양·성격은 정의(BlockDef)에서 온다. 여기서 다시 판정하면 정의가 두 곳이 된다. */
fun isC(m: Material): Boolean = defOf(m.typeId).shape == BlockShape.C_BLOCK

/** 모자인지는 도메인이 아는 성격(kind)이다. 시간축(Timeline)도 같은 기준을 쓴다. */
fun isHat(m: Material): Boolean = m.kind == Kind.HAT

/**
 * 이 블록을 그 자리에 넣을 수 있는가.
 *
 * 결합 규칙은 여기 **한 곳**에만 둔다. 드래그든 + 버튼이든 같은 판단을 쓰게 해서,
 * 경로마다 규칙이 갈리는 일이 없게 한다.
 *  - 모자: 줄기의 맨 위(index 0)에만. 위에 아무것도 없어야 하는 블록이므로.
 *    (이미 모자로 시작하는 줄기에는 그 자리가 아예 만들어지지 않는다 → 저절로 막힌다.)
 *  - 그 밖: 만들어진 자리면 어디든.
 * 마개 뒤·모자 앞처럼 "자리 자체가 없어야 하는" 규칙은 자리를 만들 때(layoutStack) 건다.
 */
fun canPlaceAt(block: Material, slot: Slot): Boolean =
    if (isHat(block)) slot.parentId == null && slot.index == 0 else true

/** 마개(아래가 평평한 블록). 뒤에 아무것도 이어붙일 수 없다 — 모양이 곧 문법이다. */
fun isCap(m: Material): Boolean = defOf(m.typeId).shape == BlockShape.CAP

/** 위 블록에서 아래 블록으로 내려갈 거리. 노치 두 겹만큼 겹쳐야 볼록이 오목에 딱 든다. */
fun meshStep(m: Material): Float = blockHeight(m) - NOTCH_DEPTH * 2f

/**
 * 자식 스택이 화면에서 차지하는 세로 길이(맞물린 상태).
 * 마지막 블록은 볼록까지 포함하므로 겹침 두 겹을 더한다.
 */
fun stackSpan(children: List<Material>): Float {
    var h = 0f
    for (c in children) h += meshStep(c)
    return h + NOTCH_DEPTH * 2f
}

/**
 * C블록 입의 높이.
 *
 * 입천장 아랫면은 y=C_MOUTH_TOP-nd 에 있고 천장 볼록이 C_MOUTH_TOP 까지 내려온다. 자식은
 * [C_INNER_TOP] 에서 시작하므로 첫 자식 윗면(C_INNER_TOP+nd)이 천장 아랫면과 만난다.
 * 바닥도 같은 기준으로 맞춰야 한다: 마지막 자식 아랫면은 C_INNER_TOP+stackSpan-nd 이고,
 * 발 윗면이 바로 거기 와야 볼록이 발 오목에 앉는다. 그래서
 *   C_MOUTH_TOP + innerHeight = C_INNER_TOP + stackSpan - nd
 * 이고, C_INNER_TOP = C_MOUTH_TOP - 2nd 이므로 innerHeight = stackSpan - 3nd 가 된다.
 * (예전엔 -nd 라서 발이 자식보다 2nd 만큼 아래로 떠 있었다.)
 */
fun innerHeight(m: Material): Float = when {
    !isC(m) -> 0f
    m.children.isEmpty() -> MOUTH_MIN
    else -> stackSpan(m.children) - NOTCH_DEPTH * 3f
}

/** C블록 입천장의 y(그리기 기준). 자식은 여기서 노치 두 겹 위에서 시작한다. */
const val C_MOUTH_TOP = C_HEADER

/**
 * 그리기가 필요로 하는 입 기하. 자식을 놓는 쪽(이 파일)이 함께 내주므로,
 * 화면 쪽에서 다시 계산하지 않는다 — 두 곳이 따로 계산하면 반드시 어긋난다.
 */
data class Mouth(val top: Float, val height: Float)

fun mouthOf(m: Material): Mouth = Mouth(C_MOUTH_TOP, innerHeight(m))

/** 자식이 C블록 안에서 시작하는 y. 천장 볼록과 물리려면 겹침 두 겹만큼 올라가야 한다. */
const val C_INNER_TOP = C_HEADER - NOTCH_DEPTH * 2f

/** 블록 한 칸의 높이. C블록은 자식에 따라 커진다. */
fun blockHeight(m: Material): Float = when {
    isC(m) -> C_HEADER + innerHeight(m) + C_FOOT
    isHat(m) -> HAT_H
    else -> ROW
}

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
        // c 앞에 끼우는 자리. 단 두 경우엔 만들지 않는다:
        //  - 모자 위에는 아무것도 못 붙는다
        //  - 바로 앞이 마개면 그 뒤로는 이어붙일 수 없다
        val afterCap = i > 0 && isCap(children[i - 1])
        if (!(i == 0 && isHat(c)) && !afterCap) out.slots.add(Slot(clumpId, parentId, i, x, y))
        out.placed.add(Placed(c, x, y, depth))

        if (isC(c)) {
            layoutStack(clumpId, c.id, c.children, x + INDENT, y + C_INNER_TOP, depth + 1, out)
        }
        // parallel(동시) 는 지금은 평범한 블록으로 둔다. 노드+갈래 UI 는 다음 업데이트에서 복원.
        y += meshStep(c)
    }
    // 맨 끝(마지막 블록 아래)에 붙이는 자리. 마개로 끝났으면 그 아래는 없다.
    val last = children.lastOrNull()
    if (last == null || !isCap(last)) out.slots.add(Slot(clumpId, parentId, children.size, x, y))
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
    Material(UUID.randomUUID().toString(), "trigger.manual", ParamBag.EMPTY)

/** 위치를 가진 새 덩어리(build). */
fun newClump(children: List<Material>, x: Float, y: Float): Material =
    Material(UUID.randomUUID().toString(), Clump.TYPE_ID, ParamBag.EMPTY, children, Meta(x = x, y = y))

/**
 * 레거시 빌드(자식=블록 스택)를 캔버스 모양(덩어리들)으로 바꾼다. 멱등:
 * 이미 캔버스(자식이 전부 build)면 그대로 둔다. 녹화가 만든 빌드는 자식이 대기/터치라 여기서 감싸진다.
 * 감쌀 때 맨 위에 모자를 얹어 "이게 실행되는 덩어리"임을 표시한다(id·이름은 캔버스가 유지).
 */
fun migrate(build: Material): Material {
    val kids = build.children
    val alreadyCanvas = kids.isNotEmpty() && kids.all { Clump.isClump(it) }
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
        if (clump.id != slot.clumpId) {
            clump
        } else {
            val inserted = insertInto(clump, slot.parentId, slot.index, blocks)
            // 덩어리 맨 위에 끼우는 경우, 덩어리는 **위로** 자라야 한다. 자리를 그대로 두면 기존
            // 블록들이 통째로 한 칸 밀려 내려가 "위에 놓았는데 아래가 움직이는" 꼴이 된다.
            // (C블록 입은 천장이 고정이라 아래로 자라는 게 맞으므로 최상위 줄기일 때만.)
            if (slot.parentId == null && slot.index == 0 && clump.children.isNotEmpty()) {
                val grow = blocks.fold(0f) { acc, b -> acc + meshStep(b) }
                inserted.copy(meta = inserted.meta.copy(y = inserted.meta.y - grow))
            } else {
                inserted
            }
        }
    }
    return canvas.copy(children = newClumps)
}

/** blocks 를 담은 새 덩어리를 (x,y) 에 추가한다. 자유 배치 드롭에 쓴다. */
fun addClump(canvas: Material, blocks: List<Material>, x: Float, y: Float): Material =
    canvas.copy(children = canvas.children + newClump(blocks, x, y))

/** parallel 등 [parentId] 블록의 자식 끝에 block 을 붙인다(갈래 추가 등). */
fun addChild(canvas: Material, parentId: String, block: Material): Material =
    canvas.copy(children = canvas.children.map { insertInto(it, parentId, Int.MAX_VALUE, listOf(block)) })


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

/**
 * [hostId] 블록의 [key] 홈에 [block] 을 꽂는다. 이미 있으면 갈아 끼운다.
 *
 * children(세로 흐름)과 달리 홈은 "값 하나"라 순서가 없다. 그래서 삽입이 아니라 교체다.
 */
fun putInSlot(root: Material, hostId: String, key: String, block: Material): Material {
    if (root.id == hostId) return root.copy(slots = root.slots + (key to block))
    return root.copy(
        children = root.children.map { putInSlot(it, hostId, key, block) },
        slots = root.slots.mapValues { (_, v) -> putInSlot(v, hostId, key, block) },
    )
}

/** [hostId] 블록의 [key] 홈을 비운다. */
fun clearSlot(root: Material, hostId: String, key: String): Material {
    if (root.id == hostId) return root.copy(slots = root.slots - key)
    return root.copy(
        children = root.children.map { clearSlot(it, hostId, key) },
        slots = root.slots.mapValues { (_, v) -> clearSlot(v, hostId, key) },
    )
}

/** id 블록을 새 값으로 교체(파라미터 편집). 캔버스 전체를 훑는다. */
fun updateBlock(root: Material, block: Material): Material {
    val kids = root.children.map { if (it.id == block.id) block else updateBlock(it, block) }
    val slots = root.slots.mapValues { (_, v) -> if (v.id == block.id) block else updateBlock(v, block) }
    return root.copy(children = kids, slots = slots)
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
    // 홈에 꽂힌 블록을 지우면 그 홈은 다시 빈 자리가 된다.
    val slots = root.slots.filterValues { it.id != id }.mapValues { (_, v) -> removeInside(v, id) }
    return root.copy(children = kids, slots = slots)
}

/** 트리에서 id 로 블록 찾기. 홈(slots)에 꽂힌 것도 트리의 일부다. */
fun findBlock(root: Material, id: String): Material? {
    if (root.id == id) return root
    for (c in root.children) findBlock(c, id)?.let { return it }
    for (v in root.slots.values) findBlock(v, id)?.let { return it }
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
    fun rec(m: Material) { s.add(m.id); m.children.forEach(::rec); m.slots.values.forEach(::rec) }
    blocks.forEach(::rec)
    return s
}

// ---- 배경 ----

/**
 * 배경 모눈.
 *
 * 화면 좌표에 그린다 — 월드 변환 안에서 그리면 격자 천이 화면 크기만큼만 있어서, 밀다 보면
 * 천 끝이 드러난다. 대신 카메라 이동을 간격으로 나눈 나머지만큼 위상을 밀고 줌만큼 간격을
 * 늘리면, 같은 무늬가 화면 어디서나 끊기지 않는다.
 */
fun DrawScope.drawGrid(color: Color, offsetX: Float, offsetY: Float, zoom: Float = 1f) {
    val gap = 74f * zoom
    if (gap <= 1f) return
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
