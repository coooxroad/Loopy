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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import kotlin.math.roundToInt
import com.loopy.app.core.material.Field
import com.loopy.app.core.material.Material
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

/**
 * 블록 설정 — 이제 BlockDef 의 Field 스키마에서 자동 생성된다.
 * 타입마다 손으로 짜던 시트/변환(applyParam·fieldLabel)이 사라졌다. 값은 ParamBag(Map)로 다룬다.
 * Field 하나를 더하면 편집 위젯이 저절로 생긴다.
 */
@Composable
fun BlockParamSheet(
    material: Material,
    onDismiss: () -> Unit,
    onSave: (Material) -> Unit,
    onDelete: () -> Unit,
    onAddFork: (() -> Unit)? = null,
) {
    val p = palette
    val def = BlockRegistry.find(material.typeId)
    var bag by remember(material.id) { mutableStateOf(material.params) }

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
                        .background(def?.color ?: p.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    LoopyIcon(def?.icon ?: com.loopy.app.ui.components.Icon.MORE, Color.White, size = 16.dp)
                }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        def?.label ?: material.typeId,
                        color = p.textStrong,
                        fontSize = Type.heading,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(def?.hint ?: "", color = p.textMuted, fontSize = Type.caption)
                }
                NeuIconButton(onClick = onDelete, size = 40.dp) {
                    LoopyIcon(com.loopy.app.ui.components.Icon.DELETE, p.danger, size = 16.dp)
                }
            }

            def?.fields?.forEach { field ->
                Spacer(Modifier.height(Space.md))
                Text(field.label, color = p.textMuted, fontSize = Type.label)
                Spacer(Modifier.height(Space.xs))
                when (field) {
                    is Field.IntSlider -> {
                        val v = bag.int(field.key, field.default)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = v.toFloat(),
                                onValueChange = { bag = bag.with(field.key, it.roundToInt()) },
                                valueRange = field.min.toFloat()..field.max.toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(Space.sm))
                            Text("$v${field.unit}", color = p.textStrong, fontSize = Type.body)
                        }
                    }

                    is Field.Toggle -> Switch(
                        checked = bag.bool(field.key, field.default),
                        onCheckedChange = { bag = bag.with(field.key, it) },
                    )

                    is Field.Choice -> Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        field.options.forEach { opt ->
                            val sel = bag.str(field.key, field.default) == opt.value
                            Box(Modifier.weight(1f)) {
                                if (sel) {
                                    NeuButton(opt.label, onClick = { bag = bag.with(field.key, opt.value) }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    NeuOutlineButton(opt.label, onClick = { bag = bag.with(field.key, opt.value) }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }

                    is Field.Seconds -> {
                        // 초로 입력받아 밀리초로 저장한다. 숫자가 아니면 값을 건드리지 않는다.
                        var text by remember(material.id, field.key) {
                            mutableStateOf(Field.Seconds.fromMs(bag.long(field.key, field.defaultMs)))
                        }
                        ParamText(text, "초") { typed ->
                            text = typed
                            Field.Seconds.toMs(typed)?.let { bag = bag.with(field.key, it) }
                        }
                    }

                    is Field.TextField -> ParamText(bag.str(field.key), field.hint) { bag = bag.with(field.key, it) }
                    is Field.AppPick -> ParamText(bag.str(field.key), "\uD328\uD0A4\uC9C0\uBA85 (\uC608: com.kakao.talk)") { bag = bag.with(field.key, it) }
                    is Field.ElementPick -> ParamText(bag.str(field.key), "\uC694\uC18C ID / \uD14D\uC2A4\uD2B8") { bag = bag.with(field.key, it) }
                }
            }

            if (onAddFork != null) {
                Spacer(Modifier.height(Space.md))
                NeuOutlineButton("\uAC08\uB798 \uCD94\uAC00", onClick = onAddFork, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(Space.md))
            NeuButton("\uC800\uC7A5", onClick = { onSave(material.copy(params = bag)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.lg))
        }
    }
}

@Composable
private fun ParamText(value: String, hint: String, onChange: (String) -> Unit) {
    val p = palette
    NeuWell(Modifier.fillMaxWidth()) {
        Box {
            if (value.isEmpty()) Text(hint, color = p.textMuted, fontSize = Type.body)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = p.textStrong, fontSize = Type.body),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
