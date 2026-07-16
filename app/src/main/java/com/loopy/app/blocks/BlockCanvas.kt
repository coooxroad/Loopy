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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 블록 캔버스.
 *
 * 무한 평면 위에 블록 더미를 놓는다. 리스트로는 중첩과 갈래가 보이지 않는다. 반복 안에
 * 무엇이 들어갔는지, 동시에 도는 갈래가 몇 개인지 눈에 보여야 조립이라 부를 수 있다.
 *
 * 동시 실행의 갈래는 캔버스에 따로 떠 있고 선으로만 이어진다. 갈래를 블록 안에 밀어 넣으면
 * 화면이 금세 좁아지지만, 노드로 두면 몇 개든 늘릴 수 있다.
 *
 * 블록은 Canvas 에 그리지 않는다. Canvas 위에는 글자를 올릴 수 없어 라벨과 값을 보여줄 수
 * 없기 때문이다. 대신 블록은 컴포저블로 두고 모양만 drawBehind 로 그린다. 배경 격자와
 * 갈래를 잇는 선만 Canvas 가 맡는다.
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
    val density = LocalDensity.current

    // 캔버스는 넓을수록 좋다. 시스템 바가 화면을 갉아먹으면 블록 놓을 자리가 줄어든다.
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

    val stacks = remember(build.id) {
        mutableStateListOf<Material>().apply { addAll(layoutStacks(build)) }
    }

    var camera by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Material?>(null) }
    // 동시 블록을 탭한 뒤 다른 블록을 탭하면 갈래로 이어진다. 선을 드래그해 잇는 방식은
    // 손가락으로는 정확히 겨냥하기 어렵다.
    var linking by remember { mutableStateOf<String?>(null) }

    fun persist() {
        MaterialStore.upsert(ctx, build.copy(children = flattenStacks(stacks)))
    }

    Box(Modifier.fillMaxSize().background(p.surface)) {
        // 배경: 격자와 갈래 연결선. 블록보다 아래에 있어야 한다.
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
            drawForkLinks(stacks, p.success.copy(alpha = 0.55f), camera, zoom)
        }

        // 블록들
        stacks.forEach { m ->
            key(m.id) {
                BlockView(
                    material = m,
                    camera = camera,
                    zoom = zoom,
                    lifted = dragId == m.id,
                    onDragStart = { dragId = m.id },
                    onDrag = { amount ->
                        val i = stacks.indexOfFirst { it.id == m.id }
                        if (i >= 0) {
                            val cur = stacks[i]
                            stacks[i] = cur.copy(
                                meta = cur.meta.copy(
                                    x = cur.meta.x + amount.x / zoom,
                                    y = cur.meta.y + amount.y / zoom,
                                ),
                            )
                        }
                    },
                    onDragEnd = {
                        snapStacks(stacks)
                        persist()
                        dragId = null
                    },
                    onClick = {
                        val src = linking
                        when {
                            src == null && m.typeId == "parallel" -> linking = m.id
                            src != null && src != m.id -> {
                                val i = stacks.indexOfFirst { it.id == m.id }
                                if (i >= 0) {
                                    val cur = stacks[i]
                                    // 이미 이어져 있으면 끊는다. 같은 동작으로 붙였다 떼는 편이 배우기 쉽다.
                                    val note = if (cur.meta.note == src) "" else src
                                    stacks[i] = cur.copy(meta = cur.meta.copy(note = note))
                                    persist()
                                }
                                linking = null
                            }
                            m.typeId == "touch" -> onOpenTouch(m)
                            else -> editing = m
                        }
                    },
                    linking = linking == m.id,
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
            NeuIconButton(
                onClick = { onRun(build.copy(children = flattenStacks(stacks))) },
                size = 40.dp,
            ) {
                LoopyIcon(Icon.PLAY, p.accent, size = 16.dp)
            }
        }

        if (stacks.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "블록을 놓아 보세요",
                    color = p.textMuted,
                    fontSize = Type.body,
                    fontWeight = FontWeight.Medium,
                )
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
                    stacks.add(
                        Material(
                            id = UUID.randomUUID().toString(),
                            typeId = spec.typeId,
                            params = defaultParams(spec.typeId),
                            meta = Meta(x = -camera.x / zoom + 40f, y = -camera.y / zoom + 120f),
                        ),
                    )
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
                    val i = stacks.indexOfFirst { it.id == updated.id }
                    if (i >= 0) stacks[i] = updated
                    persist()
                    editing = null
                },
                onDelete = {
                    stacks.removeAll { it.id == m.id }
                    persist()
                    editing = null
                },
                onAddFork = if (m.typeId == "parallel") {
                    {
                        val count = stacks.count { it.meta.note == m.id }
                        stacks.add(
                            Material(
                                id = UUID.randomUUID().toString(),
                                typeId = "build",
                                params = com.loopy.app.core.material.BuildParams(null),
                                meta = Meta(
                                    note = m.id,
                                    x = m.meta.x - 120f + count * 220f,
                                    y = m.meta.y + 140f,
                                ),
                            ),
                        )
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

/** 블록 하나. 모양은 drawBehind, 내용은 컴포저블. */
@Composable
private fun BlockView(
    material: Material,
    camera: Offset,
    zoom: Float,
    lifted: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
    linking: Boolean = false,
) {
    val spec = specOf(material.typeId)
    val density = LocalDensity.current
    val w = 200.dp
    val h = blockHeightDp(material)

    val px = with(density) { (material.meta.x * zoom + camera.x).roundToInt() }
    val py = with(density) { (material.meta.y * zoom + camera.y).roundToInt() }

    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(px, py) }
            .graphicsLayer(
                scaleX = zoom,
                scaleY = zoom,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f),
            )
            // 끌고 있는 블록이 맨 위에 있어야 어디로 가는지 보인다. 그림자는 요소 밖으로
            // 뻗어야 하므로 레이어로 가둘 수 없고, 대신 겹침 순서로 정리한다.
            .zIndex(if (lifted) 10f else 0f)
            .size(w, h)
            .blockShape(
                shape = spec.shape,
                color = spec.color,
                innerTop = with(density) { 52.dp.toPx() },
                innerHeight = with(density) { innerHeightDp(material).toPx() },
                lifted = lifted,
            )
            .pointerInput(material.id) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                    onDragEnd = { onDragEnd() },
                )
            }
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoopyIcon(spec.icon, Color.White, size = 15.dp)
            Spacer(Modifier.width(Space.sm))

            if (spec.before.isNotEmpty()) {
                Text(spec.before, color = Color.White, fontSize = Type.label, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
            }

            // 입력 홈. 모양이 무엇을 넣을 수 있는지 말한다.
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

private fun defaultParams(typeId: String) = when (typeId) {
    "wait" -> WaitParams(1000L)
    "loop" -> LoopParams(count = 2)
    "if" -> IfParams("")
    else -> NoParams
}

private fun detailOf(m: Material): String = when (val pr = m.params) {
    is WaitParams -> "%.3f초".format(pr.ms / 1000.0)
    is LoopParams -> if (pr.infinite) "무한" else "${pr.count}번"
    is IfParams -> pr.condition.ifEmpty { "조건 없음" }
    else -> ""
}

// 블록 높이는 BlockLayout.blockHeightDp / innerHeightDp 한 곳에서 정의한다.
// 스냅과 렌더가 같은 값을 보게 하려는 것이므로 여기서 따로 계산하지 않는다.

/**
 * 입력 홈.
 *
 * 둥근 홈에는 값이, 육각 홈에는 참/거짓이 들어간다. 모양이 다르면 무엇을 넣어야 할지
 * 설명할 필요가 없다. 스크래치에서 문법 오류가 나지 않는 이유가 이것이다.
 */
@Composable
private fun SlotChip(text: String, rounded: Boolean) {
    val shape = if (rounded) {
        RoundedCornerShape(50)
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
        Text(
            text.ifEmpty { " " },
            color = Color(0xFF1F2430),
            fontSize = Type.label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private fun slotText(m: Material): String = when (val pr = m.params) {
    is WaitParams -> "%.3f".format(pr.ms / 1000.0)
    is LoopParams -> if (pr.infinite) "∞" else pr.count.toString()
    is IfParams -> pr.condition.ifEmpty { "?" }
    is com.loopy.app.core.material.BrightnessParams -> pr.level.toString()
    is com.loopy.app.core.material.AppParams -> pr.pkg.ifEmpty { "앱" }
    is com.loopy.app.core.material.ShellParams -> pr.cmd.take(10).ifEmpty { "명령" }
    is com.loopy.app.core.material.VarSetParams -> pr.name.ifEmpty { "이름" }
    else -> ""
}
