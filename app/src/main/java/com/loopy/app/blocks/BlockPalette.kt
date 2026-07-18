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
fun BlockPalette(onDismiss: () -> Unit, onPick: (BlockDef) -> Unit) {
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
                // 팔레트는 이제 BlockRegistry(정의)에서 자동으로 채워진다. 블록 추가 = 정의 하나.
                BlockRegistry.all().filter { it.category == tab }.forEach { def ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { onPick(def) }
                            .background(def.color.copy(alpha = 0.12f))
                            .padding(Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(def.color),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoopyIcon(def.icon, Color.White, size = 16.dp)
                        }
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                def.label,
                                color = p.textStrong,
                                fontSize = Type.body,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(def.hint, color = p.textMuted, fontSize = Type.label)
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
                            keyboardType = when {
                                material.params is WaitParams -> KeyboardType.Decimal
                                numeric -> KeyboardType.Number
                                else -> KeyboardType.Text
                            },
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
