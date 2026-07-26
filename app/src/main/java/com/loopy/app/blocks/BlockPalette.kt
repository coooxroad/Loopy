package com.loopy.app.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.loopy.app.core.material.Field
import com.loopy.app.core.material.Kind
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
/** 팔레트에 보여줄 견본 블록. 기본값만 채운 빈 껍데기라 실제 블록과 같은 모양이 나온다. */
private fun previewOf(def: BlockDef): Material =
    Material(id = "preview-" + def.id, typeId = def.id, params = def.defaultParams())

@Composable
fun BlockTray(
    onPick: (BlockDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val density = LocalDensity.current.density
    var tab by remember { mutableStateOf(BlockCategory.ACTION) }

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
            .background(p.surface)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 색 레일 — 고른 카테고리만 크고 진하게. 블록 색이 이미 카테고리를 말하므로 글자는 겹말이다.
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            BlockCategory.entries.forEach { c ->
                val on = c == tab
                Box(
                    Modifier
                        .size(if (on) 20.dp else 14.dp)
                        .clip(CircleShape)
                        .background(colorOf(c).copy(alpha = if (on) 1f else 0.4f))
                        .clickable { tab = c },
                )
            }
        }
        Spacer(Modifier.width(Space.md))
        BlockChoices(
            defs = BlockRegistry.all().filter { it.category == tab && !isValueBlock(it) },
            density = density,
            horizontal = true,
            onPick = onPick,
        )
    }
}

/** 트레이와 홈 고르기가 함께 쓰는 블록 줄. 견본은 캔버스와 같은 렌더러로 그린다. */
@Composable
private fun BlockChoices(
    defs: List<BlockDef>,
    density: Float,
    horizontal: Boolean,
    onPick: (BlockDef) -> Unit,
) {
    val items: @Composable () -> Unit = {
        defs.forEach { def ->
            BlockFace(
                material = previewOf(def),
                density = density,
                onSocket = null, // 견본의 홈은 누르지 않는다
                gestures = Modifier.clickable { onPick(def) },
            )
        }
    }
    if (horizontal) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) { items() }
    } else {
        Column(
            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) { items() }
    }
}

/**
 * 홈에 꽂을 블록 고르기. 모양이 문법이므로 그 홈이 받는 종류만 보여준다.
 */
@Composable
fun BlockPalette(
    onDismiss: () -> Unit,
    onPick: (BlockDef) -> Unit,
    accepts: SlotKind,
) {
    val p = palette
    val density = LocalDensity.current.density
    // 홈이 받는 모양 → 그 모양을 내는 블록 종류. 아무것도 못 받는 홈이면 후보가 없다.
    val wanted: Kind? = when (accepts) {
        SlotKind.VALUE -> Kind.REPORTER
        SlotKind.BOOLEAN -> Kind.BOOLEAN
        SlotKind.NONE -> null
    }

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
                "\uD648\uC5D0 \uAF42\uAE30",
                color = p.textStrong,
                fontSize = Type.heading,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Space.md))
            BlockChoices(
                defs = if (wanted == null) emptyList() else BlockRegistry.all().filter { it.kind == wanted },
                density = density,
                horizontal = false,
                onPick = onPick,
            )
        }
    }
}

@Composable
fun BlockParamSheet(
    material: Material,
    onDismiss: () -> Unit,
    onSave: (Material) -> Unit,
    onDelete: () -> Unit,
    onAddFork: (() -> Unit)? = null,
    /** "빌드 실행"의 대상 후보. 저장된 빌드 목록을 화면에서 넘겨준다(자기 자신은 빼고). */
    builds: List<Material> = emptyList(),
    /** 홈에 꽂을 블록 고르기 열기. */
    onPickSocket: ((String, SlotKind) -> Unit)? = null,
    /** 홈 비우기. */
    onClearSocket: ((String) -> Unit)? = null,
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

            // 홈(값·조건 자리). 캔버스에서 홈을 직접 눌러도 되지만, 여기서도 채울 수 있어야
            // 작은 홈을 정확히 누르지 못해도 막히지 않는다.
            def?.slots?.forEach { sd ->
                Spacer(Modifier.height(Space.md))
                Text(sd.label.ifEmpty { sd.key }, color = p.textMuted, fontSize = Type.label)
                Spacer(Modifier.height(Space.xs))
                val filled = material.slots[sd.key]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeuButton(
                        filled?.let { BlockRegistry.find(it.typeId)?.label ?: it.typeId } ?: "\uBE14\uB85D \uAF42\uAE30",
                        onClick = { onPickSocket?.invoke(sd.key, sd.accepts) },
                        modifier = Modifier.weight(1f),
                    )
                    if (filled != null) {
                        Spacer(Modifier.width(Space.sm))
                        NeuOutlineButton("\uBE44\uC6B0\uAE30", onClick = { onClearSocket?.invoke(sd.key) })
                    }
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
                    is Field.BuildPick -> {
                        val selected = bag.str(field.key)
                        if (builds.isEmpty()) {
                            Text("\uC800\uC7A5\uB41C \uBE4C\uB4DC\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4", color = p.textMuted, fontSize = Type.body)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                builds.forEach { b ->
                                    val name = b.meta.name.ifEmpty { "\uC774\uB984 \uC5C6\uC74C" }
                                    if (b.id == selected) {
                                        NeuButton(name, onClick = { bag = bag.with(field.key, b.id) }, modifier = Modifier.fillMaxWidth())
                                    } else {
                                        NeuOutlineButton(name, onClick = { bag = bag.with(field.key, b.id) }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
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
