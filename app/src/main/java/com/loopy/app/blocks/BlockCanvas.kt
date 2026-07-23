package com.loopy.app.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    onOpenTouch: (Material) -> Unit,
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
    val drag = ui.drag
    val curDrag = drag?.blockId
    val curTgt = drag?.target
    val previewing = curDrag != null && curTgt != null
    val previewCanvas = if (curDrag != null && curTgt != null)
        insertAtSlot(detachTail(ui.canvas, curDrag).first, curTgt, tailOf(ui.canvas, curDrag))
    else ui.canvas
    val origLayout = layoutCanvas(ui.canvas)
    val previewLayout = layoutCanvas(previewCanvas)
    val ghostAt = if (previewing) previewLayout.placed.firstOrNull { it.block.id == curDrag } else null
    val dragGroup = drag?.group ?: emptySet()
    val dragDelta = drag?.delta ?: Offset.Zero
    val overTrash = drag?.overTrash ?: false

    Box(
        Modifier
            .fillMaxSize()
            .background(p.surface)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    editor.onEvent(EditorEvent.Zoom(gestureZoom))
                    editor.onEvent(EditorEvent.Pan(pan))
                }
            },
    ) {
        // 월드: 줌/이동을 통째로 건다. 블록·격자는 전부 월드 좌표(dp×density).
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
            Canvas(Modifier.fillMaxSize()) {
                drawGrid(p.shadowColor.copy(alpha = 0.10f), 0f, 0f)
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
                            innerTop = C_HEADER * density,
                            innerHeight = innerHeight(gb) * density,
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
                            editor.onEvent(EditorEvent.DragMove(amount, density, Size(screenWpx, screenHpx)))
                        },
                        onDragEnd = { editor.onEvent(EditorEvent.DragEnd) },
                        onClick = {
                            if (pl.block.typeId == "touch") onOpenTouch(pl.block)
                            else editor.onEvent(EditorEvent.OpenSheet(pl.block))
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

        NeuFab(
            onClick = { editor.onEvent(EditorEvent.OpenPalette) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
        ) {
            if (curDrag != null) {
                LoopyIcon(Icon.DELETE, if (overTrash) Color(0xFFFF5A5F) else Color.White, size = if (overTrash) 26.dp else 22.dp)
            } else {
                LoopyIcon(Icon.ADD, Color.White, size = 22.dp)
            }
        }

        if (ui.picking) {
            BlockPalette(
                onDismiss = { editor.onEvent(EditorEvent.Dismiss) },
                onPick = { def -> editor.onEvent(EditorEvent.Pick(def)) },
            )
        }

        ui.editing?.let { m ->
            BlockParamSheet(
                material = m,
                onDismiss = { editor.onEvent(EditorEvent.Dismiss) },
                onSave = { updated -> editor.onEvent(EditorEvent.SaveParams(updated)) },
                onDelete = { editor.onEvent(EditorEvent.Delete(m.id)) },
                onAddFork = if (m.typeId == "parallel") {
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
) {
    val def = defOf(material.typeId)
    val curStart by rememberUpdatedState(onDragStart)
    val curDrag by rememberUpdatedState(onDrag)
    val curEnd by rememberUpdatedState(onDragEnd)
    val curClick by rememberUpdatedState(onClick)
    val px = (xDp * density).roundToInt()
    val py = (yDp * density).roundToInt()

    Box(
        Modifier
            .offset { IntOffset(px, py) }
            .zIndex(if (lifted) 10f else 0f)
            .height(blockHeight(material).dp)
            .widthIn(min = 132.dp)
            .blockShape(
                shape = def.shape,
                color = def.color,
                innerTop = C_HEADER * density,
                innerHeight = innerHeight(material) * density,
                lifted = lifted,
            )
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
    ) {
        Row(
            Modifier.height(C_HEADER.dp).padding(start = Space.md, end = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoopyIcon(def.icon, Color.White, size = 15.dp)
            Spacer(Modifier.width(Space.sm))
            BlockSentence(def, material)
        }
    }
}

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
private fun BlockSentence(def: BlockDef, m: Material) {
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
        val boolean = def.slots.any { it.key == key && it.accepts == SlotKind.BOOLEAN }
        SlotChip(slotValue(def, m, key), rounded = !boolean)
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
