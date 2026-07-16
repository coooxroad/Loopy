package com.loopy.app.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.loopy.app.core.material.BuildParams
import com.loopy.app.core.material.IfParams
import com.loopy.app.core.material.LoopParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.Meta
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
 * 상태는 트리(root) 그 자체다. 화면 좌표는 매 프레임 트리에서 계산한다(layoutRoot). 블록은
 * 자기 좌표를 저장하지 않으므로 언제나 맞물리고, 반복 블록은 자식을 입 안에 품는다.
 *
 * 드래그는 스크래치식이다: 스택 중간 블록을 잡으면 그 아래가 함께 딸려오고, 가까운 연결
 * 자리에 하이라이트가 뜨고, 놓으면 트리에 꽂힌다. 픽셀 스냅이 아니라 트리 삽입이다.
 *
 * 트리를 드래그 중에 건드리면 제스처가 끊기므로, 끄는 동안엔 그룹을 오프셋으로 띄우기만 하고
 * 놓는 순간에 트리를 바꾼다.
 */
private const val ORIGIN_X = 40f
private const val ORIGIN_Y = 40f

@Composable
fun BlockCanvas(
    build: Material,
    onBack: () -> Unit,
    onRun: (Material) -> Unit,
    onOpenTouch: (Material) -> Unit,
) {
    val ctx = LocalContext.current
    val p = palette

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

    var root by remember(build.id) { mutableStateOf(build) }
    var camera by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Material?>(null) }

    // 드래그 상태
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragGroup by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var dragTarget by remember { mutableStateOf<Slot?>(null) }

    fun persist() { MaterialStore.upsert(ctx, root) }

    val layout = layoutRoot(root, ORIGIN_X, ORIGIN_Y)

    Box(Modifier.fillMaxSize().background(p.surface)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        camera += pan
                        zoom = (zoom * gestureZoom).coerceIn(0.5f, 2f)
                    }
                },
        ) {
            drawGrid(p.shadowColor.copy(alpha = 0.12f), camera, zoom)
            // 스냅 하이라이트: 놓으면 여기 붙는다.
            dragTarget?.let { s ->
                val hx = s.x * zoom + camera.x
                val hy = s.y * zoom + camera.y
                drawRoundRect(
                    color = p.accent.copy(alpha = 0.9f),
                    topLeft = Offset(hx, hy - 3f * zoom),
                    size = Size(180f * zoom, 6f * zoom),
                    cornerRadius = CornerRadius(3f * zoom, 3f * zoom),
                )
            }
        }

        // 트리에서 계산된 블록들
        layout.placed.forEach { pl ->
            val inDrag = pl.block.id in dragGroup
            val ox = pl.x + if (inDrag) dragDelta.x else 0f
            val oy = pl.y + if (inDrag) dragDelta.y else 0f
            key(pl.block.id) {
                BlockView(
                    material = pl.block,
                    x = ox,
                    y = oy,
                    camera = camera,
                    zoom = zoom,
                    lifted = inDrag,
                    onDragStart = {
                        val group = tailOf(root, pl.block.id)
                        dragId = pl.block.id
                        dragGroup = allIds(group)
                        dragDelta = Offset.Zero
                        dragTarget = null
                    },
                    onDrag = { amount ->
                        dragDelta += Offset(amount.x / zoom, amount.y / zoom)
                        val lay = layoutRoot(root, ORIGIN_X, ORIGIN_Y)
                        val grabbed = lay.placed.firstOrNull { it.block.id == dragId }
                        if (grabbed != null) {
                            val cx = grabbed.x + dragDelta.x
                            val cy = grabbed.y + dragDelta.y
                            val open = lay.slots.filter { it.parentId !in dragGroup }
                            dragTarget = nearestSlot(open, cx, cy)
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
                        when {
                            pl.block.typeId == "touch" -> onOpenTouch(pl.block)
                            else -> editing = pl.block
                        }
                    },
                )
            }
        }

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

        if (root.children.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("블록을 놓아 보세요", color = p.textMuted, fontSize = Type.body, fontWeight = FontWeight.Medium)
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

/** 블록 하나. 모양은 drawBehind(BlockDraw), 내용은 컴포저블. 위치는 트리에서 받는다. */
@Composable
private fun BlockView(
    material: Material,
    x: Float,
    y: Float,
    camera: Offset,
    zoom: Float,
    lifted: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
) {
    val spec = specOf(material.typeId)
    val density = LocalDensity.current
    val h = with(density) { blockHeight(material).dp }

    val px = with(density) { (x * zoom + camera.x).roundToInt() }
    val py = with(density) { (y * zoom + camera.y).roundToInt() }

    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(px, py) }
            .graphicsLayer(
                scaleX = zoom,
                scaleY = zoom,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f),
            )
            .zIndex(if (lifted) 10f else 0f)
            .height(h)
            .widthIn(min = 132.dp)
            .blockShape(
                shape = spec.shape,
                color = spec.color,
                innerTop = with(density) { C_HEADER.dp.toPx() },
                innerHeight = with(density) { innerHeight(material).dp.toPx() },
                lifted = lifted,
            )
            .pointerInput(material.id) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount -> change.consume(); onDrag(amount) },
                    onDragEnd = { onDragEnd() },
                )
            }
            .clickable(onClick = onClick),
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

/** 초를 사람이 읽기 좋게: 0.300 -> 0.3, 1.000 -> 1, 10.789 -> 10.789. */
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
    else -> com.loopy.app.core.material.NoParams
}
