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
)


/** 드래그 한 판의 상태. null 이면 드래그 아님 — "드래그 중"이 타입으로 명확해진다. */
@Immutable
data class Drag(
    val blockId: String,
    val group: Set<String>,             // 딸려오는 꼬리 id들
    val delta: Offset = Offset.Zero,    // dp
    val target: Slot? = null,           // 스냅 대상(있으면 미리보기)
    val socket: SocketRef? = null,      // 홈 위에 있으면 그 홈(값 블록일 때만)
    val overTrash: Boolean = false,
)

/** 화면이 알려준 홈의 자리. 홈 위치는 글자 길이에 달려 있어 레이아웃이 알 수 없다. */
data class SocketBox(
    val hostId: String,
    val key: String,
    val accepts: SlotKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 드래그 이벤트가 어느 좌표계에서 왔는가.
 *
 * 확대 레이어 안에서 온 제스처는 이미 배율이 벗겨져 있고, 밖에서 온 것은 화면 픽셀 그대로다.
 * 부르는 쪽은 "어디서 왔는지"만 말하고, 실제 변환은 배율을 아는 리듀서가 한다.
 */
enum class DragSpace {
    /** 캔버스 위 블록 — 확대 레이어 안. */
    WORLD,

    /** 트레이 등 레이어 밖 — 화면 픽셀. */
    SCREEN,
}

/** 어느 블록의 어느 홈인지. */
data class SocketRef(val hostId: String, val key: String)

/** 사용자가 하는 일. 화면은 제스처·탭을 이 이벤트로만 올려보낸다(계산은 안 함). */
sealed interface EditorEvent {
    data class DragStart(val blockId: String) : EditorEvent
    data class DragMove(
        /** 이번에 움직인 양(px). 어느 좌표계인지는 [space] 가 말한다. */
        val amountPx: Offset,
        /**
         * 그 픽셀이 어느 좌표계의 것인가.
         *
         * 배율로 나누는 일을 **여기(리듀서)** 에서 한다. 화면 쪽 람다는 제스처가 시작될 때의
         * 값을 붙잡고 있어서, 도중에 배율이 바뀌면 옛 값으로 계산해 버린다. 배율은 살아 있는
         * 상태를 가진 쪽이 다뤄야 한다.
         */
        val space: DragSpace,
        val density: Float,
        val screen: Size,
        /** 화면 좌표(px)로 잰 홈들. 값 블록을 끌 때만 쓰인다. */
        val sockets: List<SocketBox> = emptyList(),
    ) : EditorEvent
    object DragEnd : EditorEvent
    data class Pan(val amountPx: Offset) : EditorEvent
    data class Zoom(val factor: Float, val focus: Offset) : EditorEvent
    object OpenPalette : EditorEvent
    data class Pick(val def: BlockDef) : EditorEvent
    data class OpenSheet(val material: Material) : EditorEvent
    data class SaveParams(val updated: Material) : EditorEvent
    data class Delete(val id: String) : EditorEvent
    data class AddFork(val parentId: String) : EditorEvent

    /** 홈을 비운다. */
    data class ClearSocketAt(val hostId: String, val key: String) : EditorEvent

    /**
     * 트레이에서 블록을 집어 캔버스로 끌기 시작했다. [x],[y] 는 블록 좌상단의 월드 dp.
     *
     * 여기서 곧바로 캔버스에 만들고 **기존 드래그를 켠다**. 그래야 고스트 미리보기·연결점
     * 스냅·휴지통이 캔버스에서 끌 때와 똑같이 동작한다(끌기 경로를 두 벌로 두지 않는다).
     */
    data class SpawnDrag(val def: BlockDef, val x: Float, val y: Float) : EditorEvent

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
        s.copy(drag = Drag(
            blockId = e.blockId,
            group = allIds(tailOf(s.canvas, e.blockId)),
        ))
    }

    is EditorEvent.DragMove -> s.drag?.let { d ->
        // 화면 픽셀 → 월드 dp. 배율은 지금 이 순간의 상태에서 가져오므로 낡을 수 없다.
        val perDp = e.density * if (e.space == DragSpace.SCREEN) s.zoom else 1f
        val delta = d.delta + Offset(e.amountPx.x / perDp, e.amountPx.y / perDp)
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
            // 딸려오는 꼬리 전체를 센다. 맨 위 블록만 세면 여러 개를 끌 때 삽입 결과와 어긋난다.
            val grabbedH = topGrow(tailOf(s.canvas, d.blockId))
            val slots = layoutCanvas(detachTail(s.canvas, d.blockId).first).slots
                // 모자도 다른 블록과 똑같이 자리를 찾는다. 다만 넣을 수 있는 자리만 후보가 된다.
                .filter { grabbed == null || canPlaceAt(grabbed, it) }
                .map { sl ->
                    if (sl.parentId == null && sl.index == 0) sl.copy(y = sl.y - grabbedH) else sl
                }
            // 값 블록은 줄기 연결점 후보가 비므로(canPlaceAt=false) 대신 홈을 찾는다.
            val socket = if (overTrash || grabbed == null || !isValue(grabbed)) {
                null
            } else {
                // 끌고 있는 블록 자신(과 그 안에 꽂힌 것들)의 홈은 목표가 될 수 없다.
                // 자기 홈은 손끝을 따라다니므로, 빼지 않으면 언제나 그것부터 잡힌다.
                val mine = allIds(tailOf(s.canvas, d.blockId))
                e.sockets.firstOrNull { b ->
                    b.hostId !in mine &&
                        // 호환은 정의 층의 규칙 하나를 쓴다(불리언은 값 자리에도 들어간다).
                        accepts(b.accepts, grabbed.kind) &&
                        // 홈은 작다. 좌상단이 정확히 안에 들어와야 하면 사실상 못 꽂는다.
                        sx >= b.left - SOCKET_REACH * e.density &&
                        sx <= b.right + SOCKET_REACH * e.density &&
                        sy >= b.top - SOCKET_REACH * e.density &&
                        sy <= b.bottom + SOCKET_REACH * e.density
                }?.let { SocketRef(it.hostId, it.key) }
            }
            // 맨 위 자리는 끌고 온 줄기 전체를 위로 올려야 닿으므로 더 멀리서도 잡히게 한다.
            val target = if (overTrash) null else nearestSlot(slots, cx, cy, radius = SNAP)
            s.copy(drag = d.copy(delta = delta, overTrash = overTrash, target = target, socket = socket))
        }
    } ?: s

    is EditorEvent.DragEnd -> {
        val d = s.drag
        if (d == null) s else s.copy(canvas = commitDrag(s.canvas, d), drag = null)
    }

    is EditorEvent.Pan -> s.copy(camera = s.camera + e.amountPx)
    is EditorEvent.Zoom -> {
        val z = (s.zoom * e.factor).coerceIn(0.5f, 2.5f)
        // 손가락 사이 지점이 제자리에 남도록 카메라도 함께 옮긴다.
        // 안 그러면 화면 좌상단을 기준으로 확대돼 엉뚱한 곳이 커진다.
        s.copy(zoom = z, camera = e.focus - (e.focus - s.camera) * (z / s.zoom))
    }

    // 트레이는 여닫는 것이다 — 같은 버튼으로 닫는다.
    is EditorEvent.OpenPalette -> s.copy(picking = !s.picking)
    // 값 블록은 홈에만 들어간다. 스택에 끼우면 문법이 깨지므로 막는다(팔레트에서도 감춰 둔다).
    is EditorEvent.Pick -> if (isValueBlock(e.def)) {
        s   // 값 블록은 아무 일도 하지 않는다(트레이는 열어 둔다)
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
        s.copy(canvas = canvas)   // 트레이는 닫지 않는다 — 이어서 더 집을 수 있게
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

    is EditorEvent.SpawnDrag -> run {
        val block = Material(
            id = UUID.randomUUID().toString(),
            typeId = e.def.id,
            params = e.def.defaultParams(),
            meta = Meta(),
        )
        // picking 은 그대로 둔다. 끌기 중에만 트레이가 접히고, 놓으면 다시 올라온다
        // (닫는 것은 X 버튼의 몫).
        s.copy(
            canvas = addClump(s.canvas, listOf(block), e.x, e.y),
            drag = Drag(blockId = block.id, group = setOf(block.id)),
        )
    }

    is EditorEvent.ClearSocketAt -> s.copy(canvas = clearSlot(s.canvas, e.hostId, e.key), editing = null)

    is EditorEvent.Dismiss -> s.copy(picking = false, editing = null)
}

/** 드래그를 놓았을 때: 휴지통이면 삭제, 스냅 대상이 있으면 삽입, 없으면 자유 배치. */
private fun commitDrag(canvas: Material, d: Drag): Material {
    val id = d.blockId
    if (d.overTrash) return detachTail(canvas, id).first   // 딸려온 덩어리 통째 삭제
    val g = layoutCanvas(canvas).placed.firstOrNull { it.block.id == id } ?: return canvas
    val (newCanvas, tail) = detachTail(canvas, id)
    if (tail.isEmpty()) return canvas
    // 홈 위에서 놓았으면 그 홈에 꽂는다. 값 블록이 갈 수 있는 유일한 자리다.
    d.socket?.let { sk ->
        return putInSlot(newCanvas, sk.hostId, sk.key, tail.first())
    }
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

/** 연결점에 붙는 거리(dp). 손끝은 정확하지 않으므로 기본값보다 넉넉히 둔다. */
private const val SNAP = 34f

/** 홈에 꽂힐 때 인정하는 여유(dp). 홈 자체가 작으므로 둘레를 넉넉히 넓힌다. */
private const val SOCKET_REACH = 28f
