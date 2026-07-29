package com.loopy.app.blocks

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.loopy.app.core.material.Material
import com.loopy.app.data.MaterialStore
import com.loopy.app.ui.components.GradientText
import com.loopy.app.ui.components.Icon
import com.loopy.app.ui.components.LoopyIcon
import com.loopy.app.ui.components.NeuFab
import com.loopy.app.ui.components.NeuIconButton
import com.loopy.app.ui.theme.Space
import com.loopy.app.ui.theme.Type
import com.loopy.app.ui.theme.palette
import kotlin.math.roundToInt

/**
 * 블록 캔버스 — 자유 배치 + 좁은 스냅.
 *
 * 상태는 캔버스(build) 그 자체다. 캔버스의 자식 = 덩어리(build)들, 각자 meta.x/y 로 자기 자리를
 * 가진다. 덩어리 안쪽 좌표는 트리에서 계산한다(맞물림 보장). 좌표는 dp 로 다루고 화면엔 density
 * 를 곱해 px 로 놓는다.
 *
 * 드래그(스크래치식): 블록을 잡으면 그 아래가 딸려 떨어져 나온다. 놓으면 **놓은 그 자리에** 새
 * 덩어리로 그대로 남는다. 단, 놓는 순간 아주 가까운 실제 연결점이 있으면 그 덩어리에 합쳐진다
 * (스냅은 조립 편의 기능일 뿐, 기본은 자유 배치). 합쳐질 판정일 때만 반투명 고스트가 뜬다.
 *
 * 실행은 모자(트리거)로 시작하는 덩어리만. 모자 없는 덩어리(조각)는 저장만 되고 돌지 않는다.
 * 여는 순간 레거시 빌드는 migrate 로 캔버스 모양이 된다.
 */
@Composable
fun BlockCanvas(
    build: Material,
    onBack: () -> Unit,
    onRun: (Material) -> Unit,
    /** 전용 편집기를 여는 요청. 어느 축인지는 정의가 정하고, 무엇을 열지는 화면 밖이 정한다. */
    onOpenEditor: (EditorAxis, Material) -> Unit,
    builds: List<Material> = emptyList(),
) {
    val ctx = LocalContext.current
    val p = palette
    val density = LocalDensity.current.density
    val cfg = LocalConfiguration.current
    val screenWpx = cfg.screenWidthDp * density
    val screenHpx = cfg.screenHeightDp * density

    DisposableEffect(Unit) {
        val window = (ctx as? android.app.Activity)?.window
        val controller = window?.let {
            androidx.core.view.WindowInsetsControllerCompat(it, it.decorView)
        }
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }

    // 모든 상태·규칙은 순수 상태홀더(EditorState)에. 화면은 ui 를 그리고 제스처를 이벤트로만 넘긴다.
    val editor = remember(build.id) { EditorState(build) { MaterialStore.upsert(ctx, it) } }
    val ui = editor.ui

    // 렌더용 파생 — 상태(canvas·drag)로만 계산(상태홀더엔 안 둔다). 스냅 중이면 상대가 자리를 벌리고
    // 갈 자리에 반투명 고스트가 뜬다. 끌던 블록은 손가락을 따라간다.
    // 트레이가 닫혀도 남아 있어야 하므로 화면 쪽에 둔다.
    val recentIds = remember { mutableStateListOf<String>() }
    // 이 화면이 루트 안에서 어디부터 시작하는지(상단 바 등을 감안).
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    // 홈의 화면상 자리. 글자 길이에 따라 달라지므로 레이아웃이 아니라 화면이 알려준다.
    val socketBoxes = remember { mutableStateMapOf<String, SocketBox>() }
    // 블록 폭(월드 dp). 판이 좌표로 대상을 찾으려면 폭이 필요하다(높이는 계산으로 안다).
    val blockW = remember { mutableStateMapOf<String, Float>() }
    // 판 안에 검색줄·카테고리·블록이 모두 들어가므로 넉넉히 잡는다.
    val panelH = LocalConfiguration.current.screenHeightDp.dp * 0.58f
    val trayH = panelH
    // 끌고 있는 동안에는 접어 둔다(놓을 자리가 보여야 하므로). 손을 떼면 다시 올라온다.
    val trayShown = ui.picking && ui.drag == null
    val drag = ui.drag
    val curDrag = drag?.blockId
    val curTgt = drag?.target
    val curSocket = drag?.socket
    val previewing = curDrag != null && (curTgt != null || curSocket != null)
    val previewCanvas = when {
        curDrag == null -> ui.canvas
        // 홈에 꽂히는 미리보기 — 꽂힌 만큼 상대 블록이 넓어진 모습까지 그대로 보인다.
        curSocket != null -> detachTail(ui.canvas, curDrag).let { (rest, tail) ->
            if (tail.isEmpty()) ui.canvas else putInSlot(rest, curSocket.hostId, curSocket.key, tail.first())
        }
        // 홈에 꽂히는 미리보기 — 꽂힌 만큼 상대 블록이 넓어진 모습까지 보인다.
        curTgt != null -> insertAtSlot(detachTail(ui.canvas, curDrag).first, curTgt, tailOf(ui.canvas, curDrag))
        else -> ui.canvas
    }
    val origLayout = layoutCanvas(ui.canvas)
    val previewLayout = layoutCanvas(previewCanvas)
    // 고스트는 **실제로 이어지는 블록**을 보여준다. 아래에 붙일 땐 끌고 온 줄기의 맨 위가,
    // 맨 위에 꽂을 땐 맨 아래가 상대와 맞물린다 — 그 블록을 보여야 어디가 붙는지 읽힌다.
    val ghostId = if (curDrag == null) {
        null
    } else if (curTgt != null && curTgt.parentId == null && curTgt.index == 0) {
        tailOf(ui.canvas, curDrag).lastOrNull()?.id ?: curDrag
    } else {
        curDrag
    }
    val ghostAt = if (previewing) previewLayout.placed.firstOrNull { it.block.id == ghostId } else null
    val dragGroup = drag?.group ?: emptySet()
    val dragDelta = drag?.delta ?: Offset.Zero
    val overTrash = drag?.overTrash ?: false

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { canvasOrigin = it.positionInRoot() }
            .background(p.surface)
            ,
    ) {
        // 배경 모눈은 월드 변환 밖(화면 좌표)에 그린다. 안쪽에 두면 격자 천이 화면 크기로 잘려
        // 밀었을 때 끝이 보인다. 카메라/줌만 넘겨 같은 무늬가 무한히 이어지게 한다.
        // 화면을 옮기고 확대하는 것은 **배경**의 일이다. 트레이나 블록을 만진 손가락은
        // 여기까지 내려오지 않으므로, 따로 막을 필요가 없다.
        Canvas(
            Modifier
                .fillMaxSize()
                ,
        ) {
            drawGrid(p.shadowColor.copy(alpha = 0.10f), ui.camera.x, ui.camera.y, ui.zoom)
        }

        // 어느 블록을 잡았는가 — **판이 좌표로 찾는다**. 블록마다 제스처를 달면, 그 블록이
        // 화면에서 사라지는 순간(홈에서 빠질 때 등) 제스처의 주인도 함께 사라져 끌기가 끊긴다.
        // 스크래치·Blockly 도 작업공간이 포인터를 갖고 좌표로 대상을 찾는다.
        fun hitAtWorld(world: Offset): Pair<Material, SocketRef?>? {
            // 1) 홈에 꽂힌 블록이 먼저다(위에 얹혀 있으므로). 겹치면 가장 안쪽.
            socketBoxes.values
                .filter { b ->
                    world.x >= b.left && world.x <= b.right &&
                        world.y >= b.top && world.y <= b.bottom &&
                        findBlock(ui.canvas, b.hostId)?.slots?.get(b.key) != null
                }
                .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                ?.let { b ->
                    val inner = findBlock(ui.canvas, b.hostId)?.slots?.get(b.key)
                    if (inner != null) return inner to SocketRef(b.hostId, b.key)
                }
            // 2) 캔버스에 놓인 블록. 나중에 그린 것이 위에 있으므로 뒤에서부터 본다.
            return origLayout.placed.asReversed().firstOrNull { pl ->
                val w = blockW[pl.block.id] ?: 132f
                world.x >= pl.x && world.x <= pl.x + w &&
                    world.y >= pl.y && world.y <= pl.y + blockHeight(pl.block)
            }?.let { it.block to null }
        }

        // 제스처 람다는 pointerInput 이 다시 시작될 때까지 **처음 것을 붙잡는다**. 그 안에
        // 배치·카메라·배율·홈목록을 그대로 캡처하면 첫 화면 기준으로 굳어, 나중에 놓은 블록은
        // 아예 없는 것이 된다. 항상 최신을 보도록 감싼다.
        val hitScreen by rememberUpdatedState<(Offset) -> Pair<Material, SocketRef?>?>({ p ->
            hitAtWorld(
                Offset(
                    (p.x - ui.camera.x) / ui.zoom / density,
                    (p.y - ui.camera.y) / ui.zoom / density,
                ),
            )
        })

        val curSockets by rememberUpdatedState(socketBoxes.values.toList())

        /** 화면 좌표 → 월드 dp. 카메라·배율이 바뀌어도 늘 지금 값을 쓴다. */
        val toWorld by rememberUpdatedState<(Offset) -> Offset>({ p ->
            Offset((p.x - ui.camera.x) / ui.zoom / density, (p.y - ui.camera.y) / ui.zoom / density)
        })

        // 월드: 줌/이동을 통째로 건다. 블록은 전부 월드 좌표(dp×density).
        Box(
            Modifier
                .fillMaxSize()
                // 제스처는 판 하나가 갖는다. 누른 자리에 블록이 있으면 블록을, 빈 곳이면
                // 판을 움직인다(한 손가락 이동, 두 손가락 확대). 한 곳에서 갈라야 블록용
                // 제스처가 팬·줌을 통째로 삼키는 일이 생기지 않는다.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 좌표 환산과 대상 찾기는 감싼 쪽에 맡긴다(항상 최신 상태를 본다).
                        val hit = hitScreen(down.position)

                        if (hit != null) {
                            val (grabbed, sock) = hit
                            // 홈에서 잡았으면 먼저 뽑는다 — 뽑혀도 제스처의 주인(판)은 그대로다.
                            if (sock != null) {
                                val w = toWorld(down.position)
                                editor.onEvent(
                                    EditorEvent.PullFromSlot(sock.hostId, sock.key, w.x, w.y),
                                )
                            }
                            editor.onEvent(EditorEvent.DragStart(grabbed.id))
                            var moved = false
                            drag(down.id) { change ->
                                change.consume()
                                moved = true
                                editor.onEvent(
                                    EditorEvent.DragMove(
                                        change.positionChange(),
                                        DragSpace.SCREEN,
                                        density,
                                        Size(screenWpx, screenHpx),
                                        curSockets,
                                    ),
                                )
                            }
                            editor.onEvent(EditorEvent.DragEnd)
                            // 움직이지 않았으면 탭이다 — 전용 편집기나 파라미터 시트를 연다.
                            if (!moved) {
                                val opens = defOf(grabbed.typeId).opensEditor
                                if (opens != null) onOpenEditor(opens, grabbed)
                                else editor.onEvent(EditorEvent.OpenSheet(grabbed))
                            }
                        } else {
                            // 빈 곳: 판을 움직인다. 손가락 수로 이동과 확대를 가른다.
                            var lastGap = 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                val live = ev.changes.filter { it.pressed }
                                if (live.isEmpty()) break
                                if (live.size >= 2) {
                                    val gap = (live[0].position - live[1].position).getDistance()
                                    if (lastGap > 0f && gap > 0f) {
                                        val mid = (live[0].position + live[1].position) / 2f
                                        editor.onEvent(EditorEvent.Zoom(gap / lastGap, mid))
                                    }
                                    lastGap = gap
                                } else {
                                    lastGap = 0f
                                    editor.onEvent(EditorEvent.Pan(live[0].positionChange()))
                                }
                                live.forEach { it.consume() }
                            }
                        }
                    }
                }
                .graphicsLayer(
                    scaleX = ui.zoom,
                    scaleY = ui.zoom,
                    translationX = ui.camera.x,
                    translationY = ui.camera.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
        ) {
            // 홈에 꽂힐 자리 — 반투명한 같은 색·같은 모양으로만 보여준다.
            // 갈 자리 반투명 고스트 (스냅 중)
            ghostAt?.let { gp ->
                val gb = gp.block
                Box(
                    Modifier
                        .offset { IntOffset((gp.x * density).roundToInt(), (gp.y * density).roundToInt()) }
                        .graphicsLayer(alpha = 0.4f)
                        .height(blockHeight(gb).dp)
                        .widthIn(min = 132.dp)
                        .blockShape(
                            shape = defOf(gb.typeId).shape,
                            color = defOf(gb.typeId).color,
                            innerTop = mouthOf(gb).top * density,
                            innerHeight = mouthOf(gb).height * density,
                        ),
                ) {}
            }

            origLayout.placed.forEach { pl ->
                // 미리보기 중에는 그 판의 모습으로 그린다 — 홈이 채워지면 그 블록이 넓어진다.
                val shown = if (previewing) findBlock(previewCanvas, pl.block.id) ?: pl.block else pl.block
                val inDrag = pl.block.id in dragGroup
                // 끌던 블록은 손가락 따라(원위치+delta). 나머지는 스냅 중이면 자리 벌린 미리보기 위치로.
                val bx: Float
                val by: Float
                if (inDrag) {
                    bx = pl.x + dragDelta.x
                    by = pl.y + dragDelta.y
                } else if (previewing) {
                    val pv = previewLayout.placed.firstOrNull { it.block.id == pl.block.id }
                    bx = pv?.x ?: pl.x
                    by = pv?.y ?: pl.y
                } else {
                    bx = pl.x
                    by = pl.y
                }
                key(pl.block.id) {
                    BlockView(
                        material = shown,
                        xDp = bx,
                        yDp = by,
                        density = density,
                        lifted = inDrag,
                        onWidth = { id, w -> blockW[id] = w },
                        // 고스트는 **홈에 얹힌 복제본**에만 건다. 손끝을 따라다니는 원본까지
                        // 같이 지우면(id 가 같으므로) 내용이 빈 껍데기가 되어 폭이 무너진다.
                        ghostId = if (curSocket != null && pl.block.id != curDrag) curDrag else null,
                        onSocketBounds = { hostId, key, accepts, x, y, w, h ->
                            // 끄는 동안에는 홈 위치를 갱신하지 않는다. 미리보기로 상대 블록이
                            // 넓어지면 홈도 함께 움직이는데, 그걸 판정에 쓰면
                            // "잡힘 → 넓어짐 → 벗어남 → 좁아짐 → 잡힘"이 반복돼 고스트가 점멸한다.
                            if (ui.drag == null) {
                                socketBoxes["$hostId/$key"] = SocketBox(
                                    hostId = hostId,
                                    key = key,
                                    accepts = accepts,
                                    left = x,
                                    top = y,
                                    right = x + w,
                                    bottom = y + h,
                                )
                            }
                        },
                    )
                }
            }
        }

        // UI 오버레이 (월드 변환 밖)
        Row(
            Modifier.fillMaxWidth().padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuIconButton(onClick = onBack, size = 40.dp) {
                LoopyIcon(Icon.BACK, p.textStrong, size = 16.dp)
            }
            Spacer(Modifier.width(Space.sm))
            GradientText(
                build.meta.name.ifEmpty { "새 빌드" },
                fontSize = Type.heading,
                modifier = Modifier.weight(1f),
            )
            NeuIconButton(onClick = { onRun(ui.canvas) }, size = 40.dp) {
                LoopyIcon(Icon.PLAY, p.accent, size = 16.dp)
            }
        }

        // 열고 닫힘은 미끄러져 들어오고 나간다(툭 나타나면 어디서 왔는지 읽히지 않는다).
        val traySlide by animateDpAsState(if (trayShown) 0.dp else trayH, label = "tray")
        // + 가 그대로 돌아 x 가 된다 — 같은 버튼이 여닫이라는 뜻이 형태로 이어진다.
        val plusTurn by animateFloatAsState(if (ui.picking) 45f else 0f, label = "plus")
        val fabLift by animateDpAsState(if (trayShown) trayH else 0.dp, label = "fab")

        NeuFab(
            onClick = { editor.onEvent(EditorEvent.OpenPalette) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Space.lg)
                .padding(bottom = fabLift),
        ) {
            if (curDrag != null) {
                LoopyIcon(Icon.DELETE, if (overTrash) Color(0xFFFF5A5F) else Color.White, size = if (overTrash) 26.dp else 22.dp)
            } else {
                LoopyIcon(Icon.ADD, Color.White, size = 22.dp, modifier = Modifier.graphicsLayer { rotationZ = plusTurn })
            }
        }

        // 트레이는 화면을 덮지 않는다 — 캔버스를 보면서 블록을 집을 수 있어야 한다.
        // 닫혀도 화면 밖으로 내려갈 뿐 계속 구성해 둔다. 끌기 도중 사라지면 제스처가 끊긴다.
        BlockTray(
                onPick = { def ->
                    // 방금 쓴 것을 맨 앞으로. 목록은 짧게 유지한다.
                    recentIds.remove(def.id)
                    recentIds.add(0, def.id)
                    while (recentIds.size > RECENT_MAX) recentIds.removeAt(recentIds.lastIndex)
                    editor.onEvent(EditorEvent.Pick(def))
                },
                recent = recentIds,
                onDragStart = { def, root, grab ->
                    // 손끝을 월드 dp 로 옮긴 뒤, 블록 안에서 잡은 만큼(이미 월드 dp) 빼면 좌상단.
                    // px 단계에서 빼면 줌이 걸린 만큼 어긋난다.
                    val finger = root - canvasOrigin
                    val wx = (finger.x - ui.camera.x) / ui.zoom / density - grab.x
                    val wy = (finger.y - ui.camera.y) / ui.zoom / density - grab.y
                    recentIds.remove(def.id)
                    recentIds.add(0, def.id)
                    while (recentIds.size > RECENT_MAX) recentIds.removeAt(recentIds.lastIndex)
                    editor.onEvent(EditorEvent.SpawnDrag(def, wx, wy))
                },
                // 이후는 캔버스에서 끌 때와 완전히 같은 길을 탄다 — 고스트·스냅·휴지통이 그대로 붙는다.
                onDragMove = { amount ->
                    // 트레이는 확대 레이어 **밖**이다 → 화면 픽셀이므로 배율까지 되돌린다.
                    editor.onEvent(
                        EditorEvent.DragMove(
                            amount,
                            DragSpace.SCREEN,
                            density,
                            Size(screenWpx, screenHpx),
                            socketBoxes.values.toList(),
                        ),
                    )
                },
                onDragEnd = { editor.onEvent(EditorEvent.DragEnd) },
                panelHeight = panelH,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = traySlide),
        )


        ui.editing?.let { m ->
            BlockParamSheet(
                material = m,
                builds = builds.filter { it.id != build.id },
                onClearSocket = { key -> editor.onEvent(EditorEvent.ClearSocketAt(m.id, key)) },
                onDismiss = { editor.onEvent(EditorEvent.Dismiss) },
                onSave = { updated -> editor.onEvent(EditorEvent.SaveParams(updated)) },
                onDelete = { editor.onEvent(EditorEvent.Delete(m.id)) },
                onAddFork = if (defOf(m.typeId).parallel) {
                    { editor.onEvent(EditorEvent.AddFork(m.id)) }
                } else {
                    null
                },
            )
        }
    }
}

/** 블록 하나. 위치는 월드 dp. 화면엔 density 곱해 px. 줌/이동은 부모 컨테이너가 건다. */
@Composable
private fun BlockView(
    material: Material,
    xDp: Float,
    yDp: Float,
    density: Float,
    lifted: Boolean,

    /** 자기 폭(월드 dp)을 알린다. 판이 좌표로 블록을 찾으려면 폭을 알아야 한다. */
    onWidth: (String, Float) -> Unit = { _, _ -> },
    /** 홈의 자리를 **월드 dp** 로 알린다: hostId, key, 받는 모양, x, y, 폭, 높이. */
    onSocketBounds: (String, String, SlotKind, Float, Float, Float, Float) -> Unit =
        { _, _, _, _, _, _, _ -> },
    /** 미리보기로 얹힌 블록의 id. 그 블록만 반투명하게 그린다. */
    ghostId: String? = null,
) {
    val def = defOf(material.typeId)
    // 자기 경계를 함께 재 둔다. 홈 위치를 이 블록 기준의 **차이**로 계산하면, Compose 가 준
    // 좌표가 확대 변환을 포함하든 아니든 상관없어진다(차이에서 상쇄된다). 픽셀↔dp 배율도
    // 이미 아는 dp 높이로 스스로 보정한다 — 가정이 필요 없다.
    var selfBox by remember(material.id) { mutableStateOf<Rect?>(null) }
    val px = (xDp * density).roundToInt()
    val py = (yDp * density).roundToInt()

    BlockFace(
        material = material,
        density = density,
        lifted = lifted,
        onSocketBounds = { hostId, key, accepts, r ->
            val b = selfBox
            val dpH = blockHeight(material)
            if (b != null && b.height > 0f && dpH > 0f) {
                val perDp = b.height / dpH
                onSocketBounds(
                    hostId, key, accepts,
                    xDp + (r.left - b.left) / perDp,
                    yDp + (r.top - b.top) / perDp,
                    r.width / perDp,
                    r.height / perDp,
                )
            }
        },
        ghostId = ghostId,
        modifier = Modifier
            .offset { IntOffset(px, py) }
            .zIndex(if (lifted) 10f else 0f)
            .onGloballyPositioned { c ->
                selfBox = c.boundsInRoot()
                val dpH = blockHeight(material)
                if (dpH > 0f && c.boundsInRoot().height > 0f) {
                    onWidth(material.id, c.boundsInRoot().width / (c.boundsInRoot().height / dpH))
                }
            },
    )
}

/**
 * 블록의 **생김새**. 위치도 몸짓도 모른다 — 모양·색·아이콘·문장만 그린다.
 *
 * 캔버스와 팔레트가 이걸 함께 쓴다. 같은 블록을 두 곳에서 따로 그리면 반드시 어긋나고,
 * 새 블록을 더할 때마다 두 곳을 손봐야 한다. 한 벌로 두면 정의 하나만 늘리면 된다.
 *
 * [modifier] 는 바깥(자리잡기), [gestures] 는 안쪽(손짓)에 붙는다 — 크기·모양이 정해진
 * 뒤에 손짓이 걸려야 눌리는 범위가 블록과 정확히 같아진다.
 */
@Composable
fun BlockFace(
    material: Material,
    density: Float,
    modifier: Modifier = Modifier,
    gestures: Modifier = Modifier,
    lifted: Boolean = false,
    /** 홈이 화면에서 차지한 자리를 알린다. 끌어다 꽂을 때 목표로 쓰인다. */
    onSocketBounds: (String, String, SlotKind, Rect) -> Unit = { _, _, _, _ -> },
    /** 미리보기로 얹힌 블록의 id. 그 블록만 반투명하게 그린다. */
    ghostId: String? = null,
) {
    val def = defOf(material.typeId)
    Box(
        modifier
            // 부모(캔버스) 폭에 갇히면 중첩이 깊어질수록 더 못 커진다. 블록은 내용만큼 커야 한다.
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            .height(blockHeight(material).dp)
            // 문장 블록만 최소 너비를 갖는다. 값 블록에 같은 최소를 주면 홈 안에서 어색하게
            // 늘어나고, 홈 밖과 안의 크기가 달라 보인다.
            .widthIn(min = if (isValue(material)) 0.dp else 132.dp)
            .blockShape(
                shape = def.shape,
                color = def.color,
                innerTop = mouthOf(material).top * density,
                innerHeight = mouthOf(material).height * density,
                lifted = lifted,
            )
            .then(gestures),
    ) {
        // 값 블록은 좌우가 뾰족하거나 둥글다. 그 폭만큼 안쪽으로 들여야 내용이 끝에 물리지 않는다.
        val value = isValue(material)
        val side = if (value) (blockHeight(material) / 2f + 4f).dp else Space.md
        // 고스트는 **형체만** 보여준다. 안의 글자와 홈까지 그리면 진짜 블록과 구별이 안 되고
        // 화면이 어지럽다. 크기·색·모양만 반투명으로 남긴다.
        if (material.id != ghostId) {
            Row(
                Modifier
                    .height(contentHeight(material).dp)
                    .padding(start = side, end = if (value) side else Space.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 값 블록엔 아이콘을 두지 않는다 — 좁은 몸에 아이콘까지 넣으면 글자가 밀린다.
                if (!value) {
                    LoopyIcon(def.icon, Color.White, size = 15.dp)
                    Spacer(Modifier.width(Space.sm))
                }
                BlockSentence(def, material, onSocketBounds, ghostId)
            }
        }
    }
}

/**
 * 비어 있는 홈.
 *
 * 값 칩과 달리 **안으로 파인** 것처럼 어둡게 그린다 — 얹힌 값이 아니라 무언가를 넣는 자리라는
 * 뜻이 모양에서 읽히게. 눌러서 꽂을 블록을 고른다.
 */
@Composable
private fun SocketHole(boolean: Boolean) {
    // 꼭짓점을 **높이의 절반**으로 고정한다. 퍼센트로 자르면 좁은 상자에서 마름모가 된다.
    val shape = if (boolean) {
        androidx.compose.foundation.shape.CutCornerShape(HOLE_H.dp / 2)
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(50)
    }
    Box(
        Modifier
            .height(HOLE_H.dp)
            // 빈 자리도 "무언가 들어갈 만한 넓이"로 보여야 한다. 좁으면 흠집처럼 보인다.
            .widthIn(min = HOLE_MIN_W.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.22f)),
    )
}

/** 빈 입력 자리의 높이. 값 블록보다 살짝 낮아 "들어갈 자리"로 읽힌다. */
private const val HOLE_H = 24

/** 빈 입력 자리의 최소 너비. 육각이 마름모로 보이지 않을 만큼. */
private const val HOLE_MIN_W = 40

/**
 * 홈에 꽂힌 블록.
 *
 * 자기 모양(둥근/육각)으로 그리고, 그 안의 문장도 같은 규칙으로 그린다 — 재귀이므로
 * 홈 안의 홈도 저절로 된다. 크기는 Compose 가 내용에 맞춰 재므로 따로 계산하지 않는다.
 */
@Composable
private fun NestedBlock(
    m: Material,
    onSocketBounds: (String, String, SlotKind, Rect) -> Unit = { _, _, _, _ -> },
    ghostId: String? = null,
) {
    // 제스처를 달지 않는다 — 잡는 일은 판이 좌표로 한다. 여기서 또 달면 두 주인이 다투고,
    // 이 블록이 홈에서 빠지는 순간 사라져 끌기가 끊긴다.
    BlockFace(
        material = m,
        density = LocalDensity.current.density,
        onSocketBounds = onSocketBounds,
        ghostId = ghostId,
        modifier = Modifier.graphicsLayer(alpha = if (m.id == ghostId) 0.45f else 1f),
    )
}

/** 최근 목록에 남길 개수. 길어지면 카테고리를 가린다. */
private const val RECENT_MAX = 5


@Composable
private fun SlotChip(text: String, rounded: Boolean) {
    val shape = if (rounded) {
        androidx.compose.foundation.shape.RoundedCornerShape(50)
    } else {
        androidx.compose.foundation.shape.CutCornerShape(percent = 50)
    }
    Box(
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.ifEmpty { " " }, color = Color(0xFF1F2430), fontSize = Type.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 문장 안의 `{키}` 자리. 값 칩으로 바뀐다. */
private val SENTENCE_SLOT = Regex("\\{([A-Za-z_][\\w.]*)\\}")

/**
 * 블록 안 문장을 그린다.
 *
 * 정의의 [BlockDef.template] 을 `{키}` 기준으로 잘라, 글자는 그대로 쓰고 자리에는 값 칩을 넣는다.
 * 타입별 분기를 두지 않으므로 새 블록이 늘어도 이 함수는 그대로다.
 */
@Composable
private fun BlockSentence(
    def: BlockDef,
    m: Material,
    onSocketBounds: (String, String, SlotKind, Rect) -> Unit = { _, _, _, _ -> },
    /** 미리보기로 얹힌 블록의 id. 그 블록만 반투명하게 그린다. */
    ghostId: String? = null,
) {
    val text = def.template
    var cursor = 0
    for (hit in SENTENCE_SLOT.findAll(text)) {
        val before = text.substring(cursor, hit.range.first).trim()
        if (before.isNotEmpty()) {
            Text(before, color = Color.White, fontSize = Type.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        val key = hit.groupValues[1]
        // 모든 자리는 입력 자리다. 그림자(타이핑 칸)를 품거나, 빈 육각 구멍이거나,
        // 블록이 꽂혀 그림자를 덮고 있거나 — 셋 중 하나일 뿐 성격은 같다.
        val input = inputsOf(def).firstOrNull { it.key == key }
        val boolean = input?.accepts == SlotKind.BOOLEAN
        val filled = m.slots[key]
        Box(
            Modifier.onGloballyPositioned { c ->
                // 주인은 이 문장을 가진 블록 자신이다. 중첩돼도 자기 id 로 알린다.
                if (input != null) onSocketBounds(m.id, key, input.accepts, c.boundsInRoot())
            },
        ) {
            when {
                // 꽂힌 블록이 그림자를 덮는다.
                filled != null ->
                    NestedBlock(filled, onSocketBounds, ghostId)
                // 참/거짓 자리는 타이핑할 게 없으니 빈 구멍으로 둔다.
                boolean -> SocketHole(boolean = true)
                // 값 자리의 그림자 — 기본값을 보여주고 바로 고칠 수 있는 칸.
                else -> SlotChip(slotValue(def, m, key), rounded = true)
            }
        }
        Spacer(Modifier.width(4.dp))
        cursor = hit.range.last + 1
    }
    val tail = text.substring(cursor).trim()
    if (tail.isNotEmpty()) {
        Text(tail, color = Color.White, fontSize = Type.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 칩에 쓸 글자. 값이 비었으면 그 자리가 무엇인지(스키마의 이름) 보여준다. */
private fun slotValue(def: BlockDef, m: Material, key: String): String {
    val field = def.fields.firstOrNull { it.key == key }
    val shown = field?.display(m.params) ?: m.params.str(key)
    if (shown.isNotEmpty()) return shown
    // 비었으면 그 자리가 무엇인지 이름으로 알린다.
    return field?.label ?: def.slots.firstOrNull { it.key == key }?.label ?: ""
}
