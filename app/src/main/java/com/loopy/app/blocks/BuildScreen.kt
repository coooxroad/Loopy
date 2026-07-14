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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.loopy.app.core.material.IfParams
import com.loopy.app.core.material.LoopParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.NoParams
import com.loopy.app.core.material.TouchParams
import com.loopy.app.core.material.WaitParams
import com.loopy.app.data.MaterialStore
import com.loopy.app.ui.components.EmptyState
import com.loopy.app.ui.components.GradientText
import com.loopy.app.ui.components.Icon
import com.loopy.app.ui.components.LoopyIcon
import com.loopy.app.ui.components.NeuButton
import com.loopy.app.ui.components.NeuFab
import com.loopy.app.ui.components.NeuIconButton
import com.loopy.app.ui.components.NeuListItem
import com.loopy.app.ui.components.NeuWell
import com.loopy.app.ui.theme.Space
import com.loopy.app.ui.theme.Type
import com.loopy.app.ui.theme.palette
import java.util.UUID

/**
 * 빌드 화면.
 *
 * 블록을 세로로 쌓아 매크로를 조립한다. 편집기가 시간축으로 보여주는 것과 같은 트리를
 * 순서축으로 본다. 녹화로 만들 수 없는 것들(조건, 반복, 대기)이 여기서 붙는다.
 *
 * 중첩은 들여쓰기로 표현한다. 조건이나 반복 안에 들어간 블록은 안쪽으로 밀린다. 흐름이
 * 그대로 보이는 편이 "실패하면 12번 줄로" 같은 점프보다 이해하기 쉽다.
 */
@Composable
fun BuildScreen(
    build: Material,
    onBack: () -> Unit,
    onRun: (Material) -> Unit,
) {
    val ctx = LocalContext.current
    val p = palette

    val blocks = remember(build.id) { mutableStateListOf<Material>().apply { addAll(build.children) } }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }

    fun persist() {
        MaterialStore.upsert(ctx, build.copy(children = blocks.toList()))
    }

    Box(Modifier.fillMaxSize().background(p.surface)) {
        Column(Modifier.fillMaxSize()) {
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
                    onClick = { onRun(build.copy(children = blocks.toList())) },
                    size = 40.dp,
                ) {
                    LoopyIcon(Icon.PLAY, p.accent, size = 16.dp)
                }
            }

            if (blocks.isEmpty()) {
                Spacer(Modifier.height(Space.xxl))
                EmptyState(
                    title = "블록을 쌓아 보세요",
                    description = "대기, 반복, 조건을 조합해\n무엇을 언제 할지 정합니다",
                    action = {
                        NeuButton(
                            "블록 추가",
                            onClick = { picking = true },
                            modifier = Modifier.width(180.dp),
                        )
                    },
                )
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.sm),
                ) {
                    blocks.forEachIndexed { i, m ->
                        BlockRow(
                            material = m,
                            onClick = { editing = i },
                            onDelete = { blocks.removeAt(i); persist() },
                            onMoveUp = if (i > 0) {
                                {
                                    val tmp = blocks[i - 1]
                                    blocks[i - 1] = blocks[i]
                                    blocks[i] = tmp
                                    persist()
                                }
                            } else {
                                null
                            },
                        )
                    }
                    Spacer(Modifier.height(96.dp))
                }
            }
        }

        if (blocks.isNotEmpty()) {
            NeuFab(
                onClick = { picking = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            ) {
                LoopyIcon(Icon.ADD, Color.White, size = 22.dp)
            }
        }

        if (picking) {
            BlockPicker(
                onDismiss = { picking = false },
                onPick = { typeId ->
                    blocks.add(newBlock(typeId))
                    persist()
                    picking = false
                },
            )
        }

        editing?.let { idx ->
            if (idx < blocks.size) {
                ParamSheet(
                    material = blocks[idx],
                    onDismiss = { editing = null },
                    onSave = { updated ->
                        blocks[idx] = updated
                        persist()
                        editing = null
                    },
                )
            }
        }
    }
}

/** 블록 한 줄. 무엇을 하는 블록인지와 설정값이 한눈에 보여야 한다. */
@Composable
private fun BlockRow(
    material: Material,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
) {
    val p = palette
    val (icon, tint) = iconOf(material.typeId, p.accent, p.textMuted, p.success)

    NeuListItem(
        onClick = onClick,
        leading = { LoopyIcon(icon, tint, size = 18.dp) },
        trailing = {
            if (onMoveUp != null) {
                NeuIconButton(onClick = onMoveUp, size = 34.dp) {
                    LoopyIcon(Icon.BACK, p.textMuted, size = 13.dp)
                }
            }
            NeuIconButton(onClick = onDelete, size = 34.dp) {
                LoopyIcon(Icon.DELETE, p.danger, size = 13.dp)
            }
        },
    ) {
        Text(
            labelOf(material),
            color = p.textStrong,
            fontSize = Type.body,
            fontWeight = FontWeight.Medium,
        )
        val detail = detailOf(material)
        if (detail.isNotEmpty()) {
            Text(detail, color = p.textMuted, fontSize = Type.caption)
        }
    }
}

/** 블록 고르기. */
@Composable
private fun BlockPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val p = palette
    val options = listOf("wait", "loop", "if", "parallel", "stop")

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
            options.forEach { id ->
                val (icon, tint) = iconOf(id, p.accent, p.textMuted, p.success)
                NeuListItem(
                    onClick = { onPick(id) },
                    leading = { LoopyIcon(icon, tint, size = 18.dp) },
                ) {
                    Text(
                        labelOfType(id),
                        color = p.textStrong,
                        fontSize = Type.body,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(hintOfType(id), color = p.textMuted, fontSize = Type.caption)
                }
            }
            Spacer(Modifier.height(Space.lg))
        }
    }
}

/** 블록 설정. 타입마다 필요한 값이 다르다. */
@Composable
private fun ParamSheet(
    material: Material,
    onDismiss: () -> Unit,
    onSave: (Material) -> Unit,
) {
    val p = palette
    var text by remember {
        mutableStateOf(
            when (val pr = material.params) {
                is WaitParams -> (pr.ms / 1000.0).toString()
                is LoopParams -> pr.count.toString()
                is IfParams -> pr.condition
                else -> ""
            },
        )
    }

    val numeric = material.params is WaitParams || material.params is LoopParams

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99101218))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(Space.xl)
                .background(p.surface)
                .padding(Space.lg),
        ) {
            Text(
                labelOf(material),
                color = p.textStrong,
                fontSize = Type.heading,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Space.xs))
            Text(hintOfType(material.typeId), color = p.textMuted, fontSize = Type.caption)
            Spacer(Modifier.height(Space.md))

            if (material.params !is NoParams) {
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
                Spacer(Modifier.height(Space.md))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Box(Modifier.weight(1f)) {
                    NeuButton(
                        "저장",
                        onClick = { onSave(applyParam(material, text)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ── 표시 도우미 ──

private fun iconOf(
    typeId: String,
    accent: Color,
    muted: Color,
    success: Color,
): Pair<Icon, Color> = when (typeId) {
    "touch" -> Icon.RECORD to accent
    "wait" -> Icon.PAUSE to muted
    "loop" -> Icon.REDO to accent
    "if" -> Icon.SPLIT to accent
    "parallel" -> Icon.LIST to success
    "build" -> Icon.FOLDER to accent
    "stop" -> Icon.STOP to muted
    else -> Icon.MORE to muted
}

private fun labelOfType(typeId: String): String = when (typeId) {
    "touch" -> "터치"
    "wait" -> "대기"
    "loop" -> "반복"
    "if" -> "만약"
    "parallel" -> "동시에"
    "build" -> "빌드"
    "stop" -> "종료"
    else -> typeId
}

private fun hintOfType(typeId: String): String = when (typeId) {
    "wait" -> "초 단위로 기다립니다"
    "loop" -> "안의 블록을 여러 번 실행합니다"
    "if" -> "조건이 맞을 때만 안의 블록을 실행합니다"
    "parallel" -> "여러 갈래를 동시에 실행합니다"
    "stop" -> "실행을 즉시 멈춥니다"
    "touch" -> "녹화된 궤적을 재생합니다"
    else -> ""
}

private fun labelOf(m: Material): String = labelOfType(m.typeId)

private fun detailOf(m: Material): String = when (val pr = m.params) {
    is WaitParams -> "%.1f초".format(pr.ms / 1000.0)
    is LoopParams -> if (pr.infinite) "무한" else "${pr.count}번"
    is IfParams -> pr.condition.ifEmpty { "조건 없음" }
    is TouchParams -> ""
    else -> if (m.children.isNotEmpty()) "${m.children.size}개 블록" else ""
}

private fun newBlock(typeId: String): Material = Material(
    id = UUID.randomUUID().toString(),
    typeId = typeId,
    params = when (typeId) {
        "wait" -> WaitParams(1000L)
        "loop" -> LoopParams(count = 2)
        "if" -> IfParams("")
        else -> NoParams
    },
)

private fun applyParam(m: Material, text: String): Material = when (m.params) {
    is WaitParams -> {
        val sec = text.toDoubleOrNull() ?: 1.0
        m.copy(params = WaitParams((sec * 1000).toLong().coerceAtLeast(0L)))
    }

    is LoopParams -> {
        val n = text.toIntOrNull() ?: 1
        m.copy(params = LoopParams(count = n.coerceAtLeast(1)))
    }

    is IfParams -> m.copy(params = IfParams(text))

    else -> m
}
