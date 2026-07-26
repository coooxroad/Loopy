package com.loopy.app.blocks

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.loopy.app.core.material.Clump
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.ParamBag
import java.util.UUID

/**
 * 에디터의 전체 상태 — 하나의 불변 트리. 화면은 이걸 그리기만 한다(단방향 흐름 UDF).
 *
 * 레이아웃·프리뷰 같은 파생은 여기 두지 않는다(렌더에서 canvas·drag 로 계산). 상태는 가볍게.
 */
@Immutable
data class EditorUi(
    val canvas: Material,
    val camera: Offset = Offset.Zero,   // 화면 px 이동
    val zoom: Float = 1f,
    val drag: Drag? = null,             // 드래그 중이 아니면 null
    val picking: Boolean = false,       // 팔레트 열림
    val editing: Material? = null,      // 파라미터 시트 대상
    val socketPick: SocketPick? = null, // 홈 고르기 열림(어느 홈을 채우는 중인지)
)

/** 어느 블록의 어느 홈을 채우는 중인가. accepts 가 고를 수 있는 블록을 좁힌다. */
data class SocketPick(val hostId: String, val key: String, val accepts: SlotKind)

/** 드래그 한 판의 상태. null 이면 드래그 아님 — "드래그 중"이 타입으로 명확해진다. */
@Immutable
data class Drag(
    val blockId: String,
    val group: Set<String>,             // 딸려오는 꼬리 id들
    val delta: Offset = Offset.Zero,    // dp
    val target: Slot? = null,           // 스냅 대상(있으면 미리보기)
    val overTrash: Boolean = false,
)

/** 사용자가 하는 일. 화면은 제스처·탭을 이 이벤트로만 올려보낸다(계산은 안 함). */
sealed interface EditorEvent {
    data class DragStart(val blockId: String) : EditorEvent
    data class DragMove(val amountPx: Offset, val density: Float, val screen: Size) : EditorEvent
    object DragEnd : EditorEvent
    data class Pan(val amountPx: Offset) : EditorEvent
    data class Zoom(val factor: Float) : EditorEvent
    object OpenPalette : EditorEvent
    data class Pick(val def: BlockDef) : EditorEvent
    data class OpenSheet(val material: Material) : EditorEvent
    data class SaveParams(val updated: Material) : EditorEvent
    data class Delete(val id: String) : EditorEvent
    data class AddFork(val parentId: String) : EditorEvent
    /** 빈 홈을 눌렀다 — 무엇을 꽂을지 고르는 중. */
    data class OpenSocket(val hostId: String, val key: String, val accepts: SlotKind) : EditorEvent

    /** 고른 블록을 홈에 꽂는다. */
    data class FillSocket(val def: BlockDef) : EditorEvent

    /** 홈을 비운다. */
    data class ClearSocketAt(val hostId: String, val key: String) : EditorEvent

    object Dismiss : EditorEvent
}

private const val ORIGIN_X = 24f
private const val ORIGIN_Y = 24f

/**
 * 순수 상태 전이. 드래그/스냅/삭제/자유배치·줌·시트 규칙이 전부 여기 모인다(화면과 분리 → 눈으로
 * 검증·단위 테스트 가능). 규칙은 기존 BlockCanvas 의 것을 그대로 옮긴 것이다(동작 보존).
 */
fun reduce(s: EditorUi, e: EditorEvent): EditorUi = when (e) {
    is EditorEvent.DragStart -> {
        val block = findBlock(s.canvas, e.blockId)
        s.copy(drag = Drag(
            blockId = e.blockId,
            group = allIds(tailOf(s.canvas, e.blockId)),
        ))
    }

    is EditorEvent.DragMove -> s.drag?.let { d ->
        val delta = d.delta + Offset(e.amountPx.x / e.density, e.amountPx.y / e.density)
        // 잡은 블록의 "현재 레이아웃상 실제 위치"로 커넥터를 만든다(저장값 X — 프레임마다 조회).
        val g = layoutCanvas(s.canvas).placed.firstOrNull { it.block.id == d.blockId }
        if (g == null) {
            s.copy(drag = d.copy(delta = delta))
        } else {
            // 화면 우하단(휴지통 FAB) 위인지 — dp→px 화면 좌표로 변환.
            val sx = (g.x + delta.x) * e.density * s.zoom + s.camera.x
            val sy = (g.y + delta.y) * e.density * s.zoom + s.camera.y
            val overTrash = sx > e.screen.width * 0.66f && sy > e.screen.height * 0.80f
            val cx = g.x + delta.x
            val cy = g.y + delta.y
            // 휴지통 위면 스냅 억제(삭제 의도). 가까운 연결점 없으면 null → 자유 배치.
            // 덩어리 맨 위 연결점은 "첫 블록의 윗변"에 있다. 그대로 두면 위에 붙이려 할 때
            // 기존 블록과 겹칠 만큼 내려야 잡혀서, 판정이 아래에 있는 것처럼 느껴진다.
            // 잡은 블록 높이만큼 올려두면 "기존 바로 위"에 놓는 자리에서 잡힌다.
            val grabbed = findBlock(s.canvas, d.blockId)
            val grabbedH = grabbed?.let { meshStep(it) } ?: 0f
            val slots = layoutCanvas(detachTail(s.canvas, d.blockId).first).slots
                // 모자도 다른 블록과 똑같이 자리를 찾는다. 다만 넣을 수 있는 자리만 후보가 된다.
                .filter { grabbed == null || canPlaceAt(grabbed, it) }
                .map { sl ->
                    if (sl.parentId == null && sl.index == 0) sl.copy(y = sl.y - grabbedH) else sl
                }
            val target = if (overTrash) null else nearestSlot(slots, cx, cy)
            s.copy(drag = d.copy(delta = delta, overTrash = overTrash, target = target))
        }
    } ?: s

    is EditorEvent.DragEnd -> {
        val d = s.drag
        if (d == null) s else s.copy(canvas = commitDrag(s.canvas, d), drag = null)
    }

    is EditorEvent.Pan -> s.copy(camera = s.camera + e.amountPx)
    is EditorEvent.Zoom -> s.copy(zoom = (s.zoom * e.factor).coerceIn(0.5f, 2.5f))

    is EditorEvent.OpenPalette -> s.copy(picking = true)
    // 값 블록은 홈에만 들어간다. 스택에 끼우면 문법이 깨지므로 막는다(팔레트에서도 감춰 둔다).
    is EditorEvent.Pick -> if (isValueBlock(e.def)) {
        s.copy(picking = false)
    } else {
        val block = Material(
            id = UUID.randomUUID().toString(),
            typeId = e.def.id,
            params = e.def.defaultParams(),
            meta = Meta(),
        )
        val first = s.canvas.children.firstOrNull()
        // 붙일 자리는 레이아웃이 내주는 **진짜 연결점** 중 마지막을 쓴다. 여기서 "맨 끝"을 직접
        // 만들어 쓰면 마개 뒤처럼 붙으면 안 되는 자리에도 붙는다(연결점 규칙을 우회하게 된다).
        val end = if (first == null) {
            null
        } else {
            layoutCanvas(s.canvas).slots
                .filter { it.clumpId == first.id && canPlaceAt(block, it) }
                .maxByOrNull { it.index }
        }
        val canvas = if (end == null) {
            addClump(s.canvas, listOf(block), ORIGIN_X, ORIGIN_Y)
        } else {
            insertAtSlot(s.canvas, end, listOf(block))
        }
        s.copy(canvas = canvas, picking = false)
    }

    is EditorEvent.OpenSheet -> s.copy(editing = e.material)
    is EditorEvent.SaveParams -> s.copy(canvas = updateBlock(s.canvas, e.updated), editing = null)
    is EditorEvent.Delete -> s.copy(canvas = removeBlock(s.canvas, e.id), editing = null)
    is EditorEvent.AddFork -> {
        val branch = Material(
            id = UUID.randomUUID().toString(),
            typeId = Clump.TYPE_ID,
            params = ParamBag.EMPTY,
            meta = Meta(),
        )
        s.copy(canvas = addChild(s.canvas, e.parentId, branch), editing = null)
    }

    is EditorEvent.OpenSocket -> s.copy(socketPick = SocketPick(e.hostId, e.key, e.accepts), editing = null)

    is EditorEvent.FillSocket -> s.socketPick?.let { pick ->
        val block = Material(
            id = UUID.randomUUID().toString(),
            typeId = e.def.id,
            params = e.def.defaultParams(),
            meta = Meta(),
        )
        s.copy(canvas = putInSlot(s.canvas, pick.hostId, pick.key, block), socketPick = null)
    } ?: s

    is EditorEvent.ClearSocketAt -> s.copy(canvas = clearSlot(s.canvas, e.hostId, e.key), editing = null)

    is EditorEvent.Dismiss -> s.copy(picking = false, editing = null, socketPick = null)
}

/** 드래그를 놓았을 때: 휴지통이면 삭제, 스냅 대상이 있으면 삽입, 없으면 자유 배치. */
private fun commitDrag(canvas: Material, d: Drag): Material {
    val id = d.blockId
    if (d.overTrash) return detachTail(canvas, id).first   // 딸려온 덩어리 통째 삭제
    val g = layoutCanvas(canvas).placed.firstOrNull { it.block.id == id } ?: return canvas
    val (newCanvas, tail) = detachTail(canvas, id)
    if (tail.isEmpty()) return canvas
    val tgt = d.target
    // 넣을 수 있는 자리인지는 자리를 고를 때 이미 걸렀다. 여기서 종류를 또 따지지 않는다.
    return if (tgt != null) {
        insertAtSlot(newCanvas, tgt, tail)
    } else {
        addClump(newCanvas, tail, g.x + d.delta.x, g.y + d.delta.y)
    }
}

/**
 * 상태홀더. 화면은 remember 로 이걸 하나 들고, 제스처를 [onEvent] 로만 넘긴다.
 * 캔버스가 바뀐 전이 후에만 저장한다(줌·팬·드래그 이동은 저장 안 함).
 */
class EditorState(initial: Material, private val onPersist: (Material) -> Unit) {
    var ui by mutableStateOf(EditorUi(canvas = migrate(initial)))
        private set

    fun onEvent(e: EditorEvent) {
        val before = ui.canvas
        ui = reduce(ui, e)
        if (ui.canvas !== before) onPersist(ui.canvas)
    }
}
