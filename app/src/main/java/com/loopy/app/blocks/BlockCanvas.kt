package com.loopy.app.blocks

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
    // 판 안에 검색줄·카테고리·블록이 모두 들어가므로 넉넉히 잡는다.
    val panelH = LocalConfiguration.current.screenHeightDp.dp * 0.58f
    val trayH = panelH
    // 끌고 있는 동안에는 접어 둔다(놓을 자리가 보여야 하므로). 손을 떼면 다시 올라온다.
    val trayShown = ui.picking && ui.drag == null
    val drag = ui.drag
    val curDrag = drag?.blockId
    val curTgt = drag?.target
    val curSocket = drag?.socket
    val previewing = curDrag != null && curTgt != null
    val previewCanvas = when {
        curDrag == null -> ui.canvas
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
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        editor.onEvent(EditorEvent.Zoom(gestureZoom, centroid))
                        editor.onEvent(EditorEvent.Pan(pan))
                    }
                },
        ) {
            drawGrid(p.shadowColor.copy(alpha = 0.10f), ui.camera.x, ui.camera.y, ui.zoom)
        }

        // 월드: 줌/이동을 통째로 건다. 블록은 전부 월드 좌표(dp×density).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = ui.zoom,
                    scaleY = ui.zoom,
                    translationX = ui.camera.x,
                    translationY = ui.camera.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
        ) {
            // 홈에 꽂힐 자리 — 반투명한 같은 색·같은 모양으로만 보여준다.
            if (curSocket != null && curDrag != null) {
                val box = socketBoxes["${curSocket.hostId}/${curSocket.key}"]
                val db = findBlock(ui.canvas, curDrag)
                if (box != null && db != null) {
                    val gx = (box.left - ui.camera.x) / ui.zoom
                    val gy = (box.top - ui.camera.y) / ui.zoom
                    Box(
                        Modifier
                            .offset { IntOffset(gx.roundToInt(), gy.roundToInt()) }
                            .graphicsLayer(alpha = 0.4f)
                            .height(blockHeight(db).dp)
                            .widthIn(min = 60.dp)
                            .blockShape(
                                shape = defOf(db.typeId).shape,
                                color = defOf(db.typeId).color,
                            ),
                    ) {}
                }
            }

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
                        material = pl.block,
                        xDp = bx,
                        yDp = by,
                        density = density,
                        lifted = inDrag,
                        onDragStart = { editor.onEvent(EditorEvent.DragStart(pl.block.id)) },
                        onDrag = { amount ->
                            editor.onEvent(EditorEvent.DragMove(amount, density, Size(screenWpx, screenHpx), socketBoxes.values.toList()))
                        },
                        onDragEnd = { editor.onEvent(EditorEvent.DragEnd) },
                        onClick = {
                            // 어떤 블록이 전용 화면을 갖는지는 정의가 말한다(화면은 이름을 모른다).
                            val opens = defOf(pl.block.typeId).opensEditor
                            if (opens != null) onOpenEditor(opens, pl.block)
                            else editor.onEvent(EditorEvent.OpenSheet(pl.block))
                        },
                        onSocketBounds = { key, accepts, box ->
                            socketBoxes["${pl.block.id}/$key"] = SocketBox(
                                hostId = pl.block.id,
                                key = key,
                                accepts = accepts,
                                left = box.left - canvasOrigin.x,
                                top = box.top - canvasOrigin.y,
                                right = box.right - canvasOrigin.x,
                                bottom = box.bottom - canvasOrigin.y,
                            )
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
                    editor.onEvent(EditorEvent.DragMove(amount, density, Size(screenWpx, screenHpx), socketBoxes.values.toList()))
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
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
    onSocketBounds: (String, SlotKind, Rect) -> Unit = { _, _, _ -> },
) {
    val def = defOf(material.typeId)
    val curStart by rememberUpdatedState(onDragStart)
    val curDrag by rememberUpdatedState(onDrag)
    val curEnd by rememberUpdatedState(onDragEnd)
    val curClick by rememberUpdatedState(onClick)
    val px = (xDp * density).roundToInt()
    val py = (yDp * density).roundToInt()

    BlockFace(
        material = material,
        density = density,
        lifted = lifted,
        onSocketBounds = onSocketBounds,
        modifier = Modifier
            .offset { IntOffset(px, py) }
            .zIndex(if (lifted) 10f else 0f),
        gestures = Modifier
            .pointerInput(material.id) {
                detectTapGestures(onTap = { curClick() })
            }
            .pointerInput(material.id) {
                detectDragGestures(
                    onDragStart = { curStart() },
                    onDrag = { change, amount -> change.consume(); curDrag(amount) },
                    onDragEnd = { curEnd() },
                    onDragCancel = { curEnd() },
                )
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
    onSocketBounds: (String, SlotKind, Rect) -> Unit = { _, _, _ -> },
) {
    val def = defOf(material.typeId)
    Box(
        modifier
            .height(blockHeight(material).dp)
            .widthIn(min = 132.dp)
            .blockShape(
                shape = def.shape,
                color = def.color,
                innerTop = mouthOf(material).top * density,
                innerHeight = mouthOf(material).height * density,
                lifted = lifted,
            )
            .then(gestures),
    ) {
        Row(
            Modifier.height(rowHeight(material).dp).padding(start = Space.md, end = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoopyIcon(def.icon, Color.White, size = 15.dp)
            Spacer(Modifier.width(Space.sm))
            BlockSentence(def, material, onSocketBounds)
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
private fun SocketHole(
    text: String,
    boolean: Boolean,
    onBounds: (Rect) -> Unit = {},
) {
    val shape = if (boolean) {
        androidx.compose.foundation.shape.CutCornerShape(percent = 50)
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(50)
    }
    Box(
        Modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.ifEmpty { "+" },
            color = Color.White.copy(alpha = 0.85f),
            fontSize = Type.label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * 홈에 꽂힌 블록.
 *
 * 자기 모양(둥근/육각)으로 그리고, 그 안의 문장도 같은 규칙으로 그린다 — 재귀이므로
 * 홈 안의 홈도 저절로 된다. 크기는 Compose 가 내용에 맞춰 재므로 따로 계산하지 않는다.
 */
@Composable
private fun NestedBlock(
    m: Material,
    onSocketBounds: (String, SlotKind, Rect) -> Unit = { _, _, _ -> },
) {
    val def = defOf(m.typeId)
    Box(
        Modifier
            .height(blockHeight(m).dp)
            .blockShape(def.shape, def.color)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlockSentence(def, m, onSocketBounds)
        }
    }
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
    onSocketBounds: (String, SlotKind, Rect) -> Unit = { _, _, _ -> },
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
        // 참/거짓 홈은 육각으로 그린다 — 모양이 무엇을 넣을 수 있는지 말한다.
        val slot = def.slots.firstOrNull { it.key == key }
        val boolean = slot?.accepts == SlotKind.BOOLEAN
        val filled = m.slots[key]
        when {
            // 홈에 블록이 꽂혀 있으면 그 블록을 그 자리에 그린다(중첩).
            filled != null -> NestedBlock(filled, onSocketBounds)
            // 빈 홈이면 눌러서 꽂을 수 있다.
            slot != null ->
                SocketHole(
                    text = slotValue(def, m, key),
                    boolean = boolean,
                    onBounds = { r -> onSocketBounds(key, slot.accepts, r) },
                )
            else -> SlotChip(slotValue(def, m, key), rounded = !boolean)
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
