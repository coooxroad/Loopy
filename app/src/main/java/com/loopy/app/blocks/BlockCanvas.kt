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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
 * 블록 캔버스 — 구조 우선.
 *
 * 상태는 트리(root) 그 자체다. 좌표는 트리에서 계산한다(맞물림 보장). 좌표는 dp 로 다루고
 * 화면엔 density 를 곱해 px 로 놓는다.
 *
 * 줌/이동은 블록마다 걸지 않고 월드 컨테이너에 한 번만 건다. 블록은 월드 좌표(dp×density)에만
 * 놓이고, 컨테이너가 통째로 확대/이동한다. 그래야 좌표 변환이 한 곳에만 있어 꼬이지 않는다.
 *
 * 드래그는 스크래치식: 중간 블록을 잡으면 아래가 딸려오고, 놓을 자리에 반투명 고스트가 뜨고,
 * 놓으면 트리에 꽂힌다. 끄는 동안 트리는 그대로 두고 오프셋만 주다가 놓을 때 바꾼다.
 *
 * 스크립트는 모자(트리거)로 시작해야 실행 시작점이 생긴다. 없으면 맨 위에 하나 씌운다.
 */
private const val ORIGIN_X = 24f
private const val ORIGIN_Y = 24f

/** 스크립트 맨 위에 모자(트리거)가 없으면 씌운다. 실행 시작점. */
private fun ensureHat(build: Material): Material {
    val first = build.children.firstOrNull()
    if (first != null && isHat(first)) return build
    val hat = Material(
        id = UUID.randomUUID().toString(),
        typeId = "trigger.manual",
        params = NoParams,
        meta = Meta(),
    )
    return build.copy(children = listOf(hat) + build.children)
}

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

    var root by remember(build.id) { mutableStateOf(ensureHat(build)) }
    var cameraPx by remember { mutableStateOf(Offset.Zero) }   // 화면 px 이동
    var zoom by remember { mutableStateOf(1f) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Material?>(null) }

    var dragId by remember { mutableStateOf<String?>(null) }
    var dragGroup by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }   // dp
    var dragTarget by remember { mutableStateOf<Slot?>(null) }

    fun persist() { MaterialStore.upsert(ctx, root) }

    val layout = layoutRoot(root, ORIGIN_X, ORIGIN_Y)
    val ghostBlock = dragId?.let { findBlock(root, it) }

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
        // 월드: 줌/이동을 통째로 건다. 블록·격자·연결선은 전부 월드 좌표(dp×density).
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
                layout.links.forEach { l ->
                    drawLine(
                        color = p.success.copy(alpha = 0.6f),
                        start = Offset(l.ax * density, l.ay * density),
                        end = Offset(l.bx * density, l.by * density),
                        strokeWidth = 3f * density,
                    )
                }
            }

            // 놓을 자리: 반투명 고스트 블록(스크래치처럼 밑에 깔린다).
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
                            dragGroup = allIds(tailOf(root, pl.block.id))
                            dragDelta = Offset.Zero
                            dragTarget = null
                        },
                        onDrag = { amount ->
                            dragDelta += Offset(amount.x / density, amount.y / density)
                            val lay = layoutRoot(root, ORIGIN_X, ORIGIN_Y)
                            val grabbed = lay.placed.firstOrNull { it.block.id == dragId }
                            if (grabbed != null) {
                                val cx = grabbed.x + dragDelta.x
                                val cy = grabbed.y + dragDelta.y
                                val open = lay.slots.filter { it.parentId !in dragGroup }
                                // 반경 제한 없이 항상 가장 가까운 자리에 붙인다 → 놓으면 반드시 옮겨진다.
                                dragTarget = nearestSlot(open, cx, cy, radius = 1_000_000f)
                            }
                        },
                        onDragEnd = {
                            val id = dragId
                            val target = dragTarget
                            if (id != null && target != null) {
                                val (kept, tail) = detachFrom(root, id)
                                if (tail.isNotEmpty()) {
                                    root = insertInto(kept, target.parentId, target.index, tail)
                                    persist()
                                }
                            }
                            dragId = null
                            dragGroup = emptySet()
                            dragDelta = Offset.Zero
                            dragTarget = null
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
            NeuIconButton(onClick = { onRun(root) }, size = 40.dp) {
                LoopyIcon(Icon.PLAY, p.accent, size = 16.dp)
            }
        }

        NeuFab(
            onClick = { picking = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
        ) {
            LoopyIcon(Icon.ADD, Color.White, size = 22.dp)
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
                    root = insertInto(root, null, root.children.size, listOf(block))
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
                    root = updateBlock(root, updated)
                    persist()
                    editing = null
                },
                onDelete = {
                    root = removeBlock(root, m.id)
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
                        root = insertInto(root, m.id, m.children.size, listOf(branch))
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
                detectTapGestures(onTap = { onClick() })
            }
            .pointerInput(material.id) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount -> change.consume(); onDrag(amount) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
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
