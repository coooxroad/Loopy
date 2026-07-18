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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.loopy.app.core.material.BuildParams
import com.loopy.app.core.material.IfParams
import com.loopy.app.core.material.LoopParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.NoParams
import com.loopy.app.core.material.WaitParams
import com.loopy.app.data.MaterialStore
import com.loopy.app.ui.components.GradientText
import com.loopy.app.ui.components.Icon
import com.loopy.app.ui.components.LoopyIcon
import com.loopy.app.ui.components.NeuFab
import com.loopy.app.ui.components.NeuIconButton
import com.loopy.app.ui.theme.Space
import com.loopy.app.ui.theme.Type
import com.loopy.app.ui.theme.palette
import java.util.UUID
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

    var canvas by remember(build.id) { mutableStateOf(migrate(build)) }
    var cameraPx by remember { mutableStateOf(Offset.Zero) }   // 화면 px 이동
    var zoom by remember { mutableStateOf(1f) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Material?>(null) }

    var dragId by remember { mutableStateOf<String?>(null) }
    var dragGroup by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }   // dp
    var grabbedIsHat by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableStateOf<Slot?>(null) }
    var overTrash by remember { mutableStateOf(false) }

    fun persist() { MaterialStore.upsert(ctx, canvas) }

    val layout = layoutCanvas(canvas)
    val ghostBlock = dragId?.let { findBlock(canvas, it) }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.surface)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(0.5f, 2.5f)
                    cameraPx += pan
                }
            },
    ) {
        // 월드: 줌/이동을 통째로 건다. 블록·격자는 전부 월드 좌표(dp×density).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoom,
                    scaleY = zoom,
                    translationX = cameraPx.x,
                    translationY = cameraPx.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawGrid(p.shadowColor.copy(alpha = 0.10f), 0f, 0f)
            }

            // 놓을 자리: 합쳐질 판정일 때만 반투명 고스트(스크래치처럼 밑에 깔린다).
            if (ghostBlock != null) {
                dragTarget?.let { s ->
                    Box(
                        Modifier
                            .offset { IntOffset((s.x * density).roundToInt(), (s.y * density).roundToInt()) }
                            .graphicsLayer(alpha = 0.38f)
                            .height(blockHeight(ghostBlock).dp)
                            .widthIn(min = 132.dp)
                            .blockShape(
                                shape = specOf(ghostBlock.typeId).shape,
                                color = specOf(ghostBlock.typeId).color,
                                innerTop = C_HEADER * density,
                                innerHeight = innerHeight(ghostBlock) * density,
                            ),
                    ) {}
                }
            }

            layout.placed.forEach { pl ->
                val inDrag = pl.block.id in dragGroup
                val bx = pl.x + if (inDrag) dragDelta.x else 0f
                val by = pl.y + if (inDrag) dragDelta.y else 0f
                key(pl.block.id) {
                    BlockView(
                        material = pl.block,
                        xDp = bx,
                        yDp = by,
                        density = density,
                        lifted = inDrag,
                        onDragStart = {
                            dragId = pl.block.id
                            dragGroup = allIds(tailOf(canvas, pl.block.id))
                            grabbedIsHat = isHat(pl.block)
                            dragDelta = Offset.Zero
                            dragTarget = null
                        },
                        onDrag = { amount ->
                            dragDelta += Offset(amount.x / density, amount.y / density)
                            val id = dragId
                            // 잡은 블록의 "현재 레이아웃상 실제 위치"를 조회해 커넥터를 만든다(저장값 X).
                            val g = id?.let { theId -> layoutCanvas(canvas).placed.firstOrNull { it.block.id == theId } }
                            if (id != null && g != null) {
                                // 화면 우하단(휴지통 FAB) 위에 있는지. dp→px 화면 좌표로 변환.
                                val sx = (g.x + dragDelta.x) * density * zoom + cameraPx.x
                                val sy = (g.y + dragDelta.y) * density * zoom + cameraPx.y
                                overTrash = sx > screenWpx * 0.66f && sy > screenHpx * 0.80f
                                val cx = g.x + dragDelta.x
                                val cy = g.y + dragDelta.y
                                // 꼬리를 먼저 떼어낸 나머지에서만 연결점 탐색 → 제자리 재흡착 방지.
                                // 윗변(cx,cy)은 "아래로 붙이기", 아랫변(cx,cy+botOff)은 "위로 얹기"를 잡는다.
                                // 휴지통 위면 스냅 억제(삭제 의도). 가까운 연결점 없으면 null → 자유 배치.
                                val firstTail = tailOf(canvas, id).firstOrNull()
                                val botOff = if (firstTail != null) meshStep(firstTail) else 0f
                                dragTarget = if (grabbedIsHat || overTrash) null else
                                    nearestSlot2(layoutCanvas(detachTail(canvas, id).first).slots, cx, cy, cx, cy + botOff)
                            }
                        },
                        onDragEnd = {
                            val id = dragId
                            if (id != null) {
                                if (overTrash) {
                                    // 휴지통에 놓음 → 딸려온 덩어리 통째 삭제.
                                    canvas = detachTail(canvas, id).first
                                    persist()
                                } else {
                                    val g = layoutCanvas(canvas).placed.firstOrNull { it.block.id == id }
                                    val (newCanvas, tail) = detachTail(canvas, id)
                                    if (tail.isNotEmpty() && g != null) {
                                        val tgt = dragTarget
                                        canvas = if (tgt != null && !isHat(tail.first())) {
                                            insertAtSlot(newCanvas, tgt, tail)
                                        } else {
                                            addClump(newCanvas, tail, g.x + dragDelta.x, g.y + dragDelta.y)
                                        }
                                        persist()
                                    }
                                }
                            }
                            dragId = null
                            dragGroup = emptySet()
                            dragDelta = Offset.Zero
                            dragTarget = null
                            overTrash = false
                        },
                        onClick = {
                            if (pl.block.typeId == "touch") onOpenTouch(pl.block) else editing = pl.block
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
            NeuIconButton(onClick = { onRun(canvas) }, size = 40.dp) {
                LoopyIcon(Icon.PLAY, p.accent, size = 16.dp)
            }
        }

        NeuFab(
            onClick = { picking = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
        ) {
            if (dragId != null) {
                LoopyIcon(Icon.DELETE, if (overTrash) Color(0xFFFF5A5F) else Color.White, size = if (overTrash) 26.dp else 22.dp)
            } else {
                LoopyIcon(Icon.ADD, Color.White, size = 22.dp)
            }
        }

        if (picking) {
            BlockPalette(
                onDismiss = { picking = false },
                onPick = { spec ->
                    val block = Material(
                        id = UUID.randomUUID().toString(),
                        typeId = spec.typeId,
                        params = defaultParams(spec.typeId),
                        meta = Meta(),
                    )
                    val first = canvas.children.firstOrNull()
                    canvas = if (isHat(block) || first == null) {
                        // 모자는 새 덩어리(스크립트)로. 덩어리가 없어도 새로 만든다.
                        addClump(canvas, listOf(block), ORIGIN_X, ORIGIN_Y)
                    } else {
                        // 그 외엔 첫 덩어리 맨 끝에 붙인다.
                        insertAtSlot(canvas, Slot(first.id, null, first.children.size, 0f, 0f), listOf(block))
                    }
                    persist()
                    picking = false
                },
            )
        }

        editing?.let { m ->
            BlockParamSheet(
                material = m,
                onDismiss = { editing = null },
                onSave = { updated ->
                    canvas = updateBlock(canvas, updated)
                    persist()
                    editing = null
                },
                onDelete = {
                    canvas = removeBlock(canvas, m.id)
                    persist()
                    editing = null
                },
                onAddFork = if (m.typeId == "parallel") {
                    {
                        val branch = Material(
                            id = UUID.randomUUID().toString(),
                            typeId = "build",
                            params = BuildParams(null),
                            meta = Meta(),
                        )
                        canvas = addChild(canvas, m.id, branch)
                        persist()
                        editing = null
                    }
                } else {
                    null
                },
            )
        }
    }
}

private const val ORIGIN_X = 24f
private const val ORIGIN_Y = 24f
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
    val spec = specOf(material.typeId)
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
                shape = spec.shape,
                color = spec.color,
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
            LoopyIcon(spec.icon, Color.White, size = 15.dp)
            Spacer(Modifier.width(Space.sm))
            if (spec.before.isNotEmpty()) {
                Text(spec.before, color = Color.White, fontSize = Type.label, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
            }
            when (spec.slot) {
                SlotKind.VALUE -> SlotChip(slotText(material), rounded = true)
                SlotKind.BOOLEAN -> SlotChip(slotText(material), rounded = false)
                SlotKind.NONE -> Unit
            }
            if (spec.slot != SlotKind.NONE) Spacer(Modifier.width(4.dp))
            Text(
                if (spec.after.isNotEmpty()) spec.after else spec.label,
                color = Color.White,
                fontSize = Type.label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
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

/** 초를 읽기 좋게: 0.300 -> 0.3, 1.000 -> 1, 10.789 -> 10.789. */
private fun fmtSec(ms: Long): String {
    val v = java.math.BigDecimal.valueOf(ms).movePointLeft(3).stripTrailingZeros()
    return if (v.signum() == 0) "0" else v.toPlainString()
}

private fun slotText(m: Material): String = when (val pr = m.params) {
    is WaitParams -> fmtSec(pr.ms)
    is LoopParams -> if (pr.infinite) "\u221E" else pr.count.toString()
    is IfParams -> pr.condition.ifEmpty { "?" }
    is com.loopy.app.core.material.BrightnessParams -> pr.level.toString()
    is com.loopy.app.core.material.AppParams -> pr.pkg.ifEmpty { "\uC571" }
    is com.loopy.app.core.material.ShellParams -> pr.cmd.take(10).ifEmpty { "\uBA85\uB839" }
    is com.loopy.app.core.material.VarSetParams -> pr.name.ifEmpty { "\uC774\uB984" }
    else -> ""
}

private fun defaultParams(typeId: String): com.loopy.app.core.material.Params = when (typeId) {
    "wait" -> WaitParams(1000L)
    "loop" -> LoopParams(count = 2)
    "if" -> IfParams("")
    "build" -> BuildParams(null)
    "screen.brightness" -> com.loopy.app.core.material.BrightnessParams(128)
    "screen.dim" -> com.loopy.app.core.material.DimParams(false)
    "var.set" -> com.loopy.app.core.material.VarSetParams("", "", false)
    "app.launch" -> com.loopy.app.core.material.AppParams("")
    "shell" -> com.loopy.app.core.material.ShellParams("")
    "touch" -> com.loopy.app.core.material.TouchParams("")
    else -> NoParams
}
