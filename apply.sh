#!/data/data/com.termux/files/usr/bin/bash
# 2/2: 캔버스(몰입/슬롯/탭연결) + 팔레트
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat > "app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt" << 'LOOPY_EOF'
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
    is WaitParams -> "%.1f초".format(pr.ms / 1000.0)
    is LoopParams -> if (pr.infinite) "무한" else "${pr.count}번"
    is IfParams -> pr.condition.ifEmpty { "조건 없음" }
    else -> ""
}

private fun blockHeightDp(m: Material): Dp = when (specOf(m.typeId).shape) {
    BlockShape.C_BLOCK -> 52.dp + innerHeightDp(m) + 30.dp
    BlockShape.HAT -> 62.dp
    else -> 52.dp
}

private fun innerHeightDp(m: Material): Dp =
    if (specOf(m.typeId).shape == BlockShape.C_BLOCK) {
        (m.children.size * 52).dp.coerceAtLeast(30.dp)
    } else {
        0.dp
    }

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
    is WaitParams -> "%.1f".format(pr.ms / 1000.0)
    is LoopParams -> if (pr.infinite) "∞" else pr.count.toString()
    is IfParams -> pr.condition.ifEmpty { "?" }
    is com.loopy.app.core.material.BrightnessParams -> pr.level.toString()
    is com.loopy.app.core.material.AppParams -> pr.pkg.ifEmpty { "앱" }
    is com.loopy.app.core.material.ShellParams -> pr.cmd.take(10).ifEmpty { "명령" }
    is com.loopy.app.core.material.VarSetParams -> pr.name.ifEmpty { "이름" }
    else -> ""
}
LOOPY_EOF
cat > "app/src/main/java/com/loopy/app/blocks/BlockPalette.kt" << 'LOOPY_EOF'
package com.loopy.app.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.loopy.app.core.material.AppParams
import com.loopy.app.core.material.BrightnessParams
import com.loopy.app.core.material.IfParams
import com.loopy.app.core.material.LoopParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.NoParams
import com.loopy.app.core.material.ShellParams
import com.loopy.app.core.material.VarSetParams
import com.loopy.app.core.material.WaitParams
import com.loopy.app.ui.components.LoopyIcon
import com.loopy.app.ui.components.NeuButton
import com.loopy.app.ui.components.NeuIconButton
import com.loopy.app.ui.components.NeuOutlineButton
import com.loopy.app.ui.components.NeuWell
import com.loopy.app.ui.theme.Radius
import com.loopy.app.ui.theme.Space
import com.loopy.app.ui.theme.Type
import com.loopy.app.ui.theme.palette

/**
 * 블록 팔레트.
 *
 * 종류가 늘어날수록 한 목록에 다 담을 수 없다. 기능별 탭으로 나누면 무엇이 어디 있는지
 * 기억하기 쉽고, 새 블록이 추가되어도 자리가 정해져 있다.
 */
@Composable
fun BlockPalette(onDismiss: () -> Unit, onPick: (BlockSpec) -> Unit) {
    val p = palette
    var tab by remember { mutableStateOf(BlockCategory.ACTION) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99101218))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(p.surface)
                .padding(Space.lg),
        ) {
            Text(
                "블록 추가",
                color = p.textStrong,
                fontSize = Type.heading,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Space.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                BlockCategory.entries.forEach { c ->
                    Box(Modifier.weight(1f)) {
                        if (c == tab) {
                            NeuButton(c.label, onClick = { tab = c }, modifier = Modifier.fillMaxWidth())
                        } else {
                            NeuOutlineButton(c.label, onClick = { tab = c }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.md))

            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                BlockSpecs.filter { it.category == tab }.forEach { spec ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { onPick(spec) }
                            .background(spec.color.copy(alpha = 0.12f))
                            .padding(Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(spec.color),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoopyIcon(spec.icon, Color.White, size = 16.dp)
                        }
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                spec.label,
                                color = p.textStrong,
                                fontSize = Type.body,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(spec.hint, color = p.textMuted, fontSize = Type.label)
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.lg))
        }
    }
}

/** 블록 설정. 타입마다 필요한 값이 다르다. */
@Composable
fun BlockParamSheet(
    material: Material,
    onDismiss: () -> Unit,
    onSave: (Material) -> Unit,
    onDelete: () -> Unit,
    onAddFork: (() -> Unit)? = null,
) {
    val p = palette
    val spec = specOf(material.typeId)

    var text by remember {
        mutableStateOf(
            when (val pr = material.params) {
                is WaitParams -> (pr.ms / 1000.0).toString()
                is LoopParams -> pr.count.toString()
                is IfParams -> pr.condition
                is BrightnessParams -> pr.level.toString()
                is AppParams -> pr.pkg
                is ShellParams -> pr.cmd
                is VarSetParams -> pr.value
                else -> ""
            },
        )
    }
    var name by remember {
        mutableStateOf((material.params as? VarSetParams)?.name.orEmpty())
    }

    val numeric = material.params is WaitParams ||
        material.params is LoopParams ||
        material.params is BrightnessParams

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99101218))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
                .background(p.surface)
                .padding(Space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(spec.color),
                    contentAlignment = Alignment.Center,
                ) {
                    LoopyIcon(spec.icon, Color.White, size = 16.dp)
                }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        spec.label,
                        color = p.textStrong,
                        fontSize = Type.heading,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(spec.hint, color = p.textMuted, fontSize = Type.caption)
                }
                NeuIconButton(onClick = onDelete, size = 40.dp) {
                    LoopyIcon(com.loopy.app.ui.components.Icon.DELETE, p.danger, size = 16.dp)
                }
            }

            if (material.params is VarSetParams) {
                Spacer(Modifier.height(Space.md))
                Text("이름", color = p.textMuted, fontSize = Type.label)
                Spacer(Modifier.height(Space.xs))
                NeuWell(Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(color = p.textStrong, fontSize = Type.body),
                        cursorBrush = SolidColor(p.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (material.params !is NoParams) {
                Spacer(Modifier.height(Space.md))
                Text(fieldLabel(material), color = p.textMuted, fontSize = Type.label)
                Spacer(Modifier.height(Space.xs))
                NeuWell(Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = p.textStrong, fontSize = Type.body),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                        ),
                        cursorBrush = SolidColor(p.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (onAddFork != null) {
                Spacer(Modifier.height(Space.md))
                NeuOutlineButton(
                    "갈래 추가",
                    onClick = onAddFork,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(Space.md))
            NeuButton(
                "저장",
                onClick = { onSave(applyParam(material, text, name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.lg))
        }
    }
}

private fun fieldLabel(m: Material): String = when (m.params) {
    is WaitParams -> "초"
    is LoopParams -> "횟수"
    is IfParams -> "조건 (예: {slock} == 1)"
    is BrightnessParams -> "밝기 (0~255, -1은 자동)"
    is AppParams -> "패키지 이름"
    is ShellParams -> "명령"
    is VarSetParams -> "값"
    else -> ""
}

private fun applyParam(m: Material, text: String, name: String): Material = when (m.params) {
    is WaitParams -> m.copy(
        params = WaitParams(((text.toDoubleOrNull() ?: 1.0) * 1000).toLong().coerceAtLeast(0L)),
    )

    is LoopParams -> m.copy(
        params = LoopParams(count = (text.toIntOrNull() ?: 1).coerceAtLeast(1)),
    )

    is IfParams -> m.copy(params = IfParams(text))

    is BrightnessParams -> m.copy(
        params = BrightnessParams(text.toIntOrNull() ?: 0),
    )

    is AppParams -> m.copy(params = AppParams(text))

    is ShellParams -> m.copy(params = ShellParams(text))

    is VarSetParams -> m.copy(
        params = VarSetParams(name = name, value = text, global = true),
    )

    else -> m
}
LOOPY_EOF
echo "2/2 완료."
git add -A
git commit -m "블록 개선: 트리거(모자) 블록, 입력 슬롯 (값)/<조건> 모양으로 문법 강제, 스냅 정확히+체인 지원, 몰입 모드, parallel은 탭으로 노드 연결, 연결선 굵게, 끌린 블록 zIndex"
git push
echo "푸시 완료"

