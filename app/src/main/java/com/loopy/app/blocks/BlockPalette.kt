package com.loopy.app.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.loopy.app.core.material.Field
import com.loopy.app.core.material.Kind
import com.loopy.app.core.material.Material
import com.loopy.app.ui.components.Icon
import com.loopy.app.ui.components.LoopyIcon
import com.loopy.app.ui.components.NeuButton
import com.loopy.app.ui.components.NeuIconButton
import com.loopy.app.ui.components.NeuOutlineButton
import com.loopy.app.ui.components.NeuWell
import com.loopy.app.ui.theme.Radius
import com.loopy.app.ui.theme.Space
import com.loopy.app.ui.theme.Type
import com.loopy.app.ui.theme.neu
import com.loopy.app.ui.theme.Depth
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
    panelHeight: Dp,
    modifier: Modifier = Modifier,
    /** 최근 집은 블록의 typeId (새 것이 앞). 카테고리를 헤매지 않고 바로 다시 집으라고. */
    recent: List<String> = emptyList(),
) {
    val p = palette
    var tab by remember { mutableStateOf(BlockCategory.ACTION) }
    var query by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    var kindFilter by remember { mutableStateOf<Kind?>(null) }

    // 판만 솟는다. 안쪽은 평평하게 두고, 영역 구분은 색조 차이로만 준다.
    Column(
        modifier
            .fillMaxWidth()
            .height(panelHeight)
            .neu(corner = Radius.lg, depth = Depth.LG)
            .clip(RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg))
            .background(p.surface)
            .padding(Space.md),
    ) {
        // ── 맨 위: 검색 + 조율(검색 설정) ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .neu(corner = Radius.sm, depth = Depth.SM, pressed = true)
                    .clip(RoundedCornerShape(Radius.sm))
                    // 위아래로 얇게 — 검색줄이 두꺼우면 블록 자리를 잡아먹는다.
                    .padding(horizontal = Space.md, vertical = Space.xs),
            ) {
                if (query.isEmpty()) {
                    Text("\uAC80\uC0C9", color = p.textMuted, fontSize = Type.label)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = p.textStrong, fontSize = Type.label),
                    cursorBrush = SolidColor(p.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(Space.sm))
            // 버튼 껍데기 없이 아이콘만. 검색줄 옆에 조용히 붙어 있어야 한다.
            LoopyIcon(
                Icon.TUNE,
                if (showFilter) p.accent else p.textMuted,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { showFilter = !showFilter }
                    .padding(Space.xs),
                size = 18.dp,
            )
        }

        // 검색 설정 — 옵션은 계속 늘어날 자리다(가로로 밀린다).
        if (showFilter) {
            Spacer(Modifier.height(Space.sm))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                KindChip("\uC804\uCCB4", kindFilter == null) { kindFilter = null }
                KindChip("\uD2B8\uB9AC\uAC70", kindFilter == Kind.HAT) { kindFilter = Kind.HAT }
                KindChip("\uD589\uB3D9", kindFilter == Kind.ACTION) { kindFilter = Kind.ACTION }
                KindChip("\uC81C\uC5B4", kindFilter == Kind.CONTROL) { kindFilter = Kind.CONTROL }
            }
        }

        Spacer(Modifier.height(Space.sm))

        // ── 아래: 왼쪽 카테고리 / 오른쪽 블록 ──
        Row(Modifier.weight(1f)) {
            Column(
                Modifier
                    .weight(0.38f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                BlockCategory.entries.forEach { c ->
                    val on = c == tab
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { tab = c }
                            .padding(horizontal = Space.sm, vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 고른 것만 색이 살아나며 **번진다**. 나머지는 판 쪽으로 섞여 가라앉는다.
                        Box(
                            Modifier
                                .size(10.dp)
                                .then(
                                    if (on) {
                                        // 자기 색으로만 번진다. 팔레트 명암을 섞으면 색이 탁해진다.
                                        Modifier.dotGlow(colorOf(c))
                                    } else {
                                        Modifier
                                            .clip(CircleShape)
                                            .background(lerp(colorOf(c), p.surface, 0.55f))
                                    },
                                ),
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            c.label,
                            color = if (on) p.textStrong else p.textMuted,
                            fontSize = Type.label,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }

                // 아래 남는 자리는 "최근"이 채운다. 방금 쓴 블록을 또 쓰는 일이 가장 잦다.
                val recentDefs = recent.mapNotNull { id -> BlockRegistry.find(id) }
                if (recentDefs.isNotEmpty()) {
                    Spacer(Modifier.height(Space.md))
                    Text("\uCD5C\uADFC", color = p.textMuted, fontSize = Type.label)
                    Spacer(Modifier.height(Space.xs))
                    recentDefs.forEach { def ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.sm))
                                .clickable { onPick(def) }
                                .padding(horizontal = Space.sm, vertical = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(colorOf(def.category)),
                            )
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                def.label,
                                color = p.textStrong,
                                fontSize = Type.label,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(Space.sm))

            // 검색어가 있으면 카테고리를 넘어 전체에서 찾는다 — 찾는 사람은 분류를 모른다.
            val searching = query.isNotBlank()
            val defs = BlockRegistry.all().filter { d ->
                !isValueBlock(d) &&
                    (kindFilter == null || d.kind == kindFilter) &&
                    (
                        if (searching) {
                            d.label.contains(query, true) || d.template.contains(query, true)
                        } else {
                            d.category == tab
                        }
                        )
            }
            // 블록 자리는 평평한 한 톤으로 구분한다(그림자를 또 쓰면 층이 겹친다).
            Box(
                Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(lerp(p.surface, p.shadowColor, 0.06f))
                    // 블록의 색 번짐은 블록 밖으로 꽤 뻗는다. 여백이 좁으면 번짐이 시작하자마자
                    // 잘려 부자연스럽다. 번짐이 **둥근 사각 경계에 닿을 때** 잘리도록 넉넉히 둔다.
                    .padding(BLOOM_ROOM),
            ) {
                BlockChoices(defs = defs, onPick = onPick, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** 블록의 색 번짐이 숨 쉴 여백. 번짐 반경(블러×퍼짐)에 맞춘 값. */
private val BLOOM_ROOM = 14.dp

/**
 * 자기 색으로 번지는 점.
 *
 * 뉴모피즘 명암(팔레트의 밝음/어두움)을 섞지 않는다 — 작은 점에서는 그게 색을 덮어 회색으로
 * 보인다. 자기 색 하나만 넓게 번지게 하면 "불이 켜진 점"으로 읽힌다.
 */
private fun Modifier.dotGlow(color: Color): Modifier = drawBehind {
    drawIntoCanvas { canvas ->
        val r = size.minDimension / 2f
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = color.toArgb()
            setShadowLayer(r * 2.2f, 0f, 0f, color.copy(alpha = 0.75f).toArgb())
        }
        canvas.nativeCanvas.drawCircle(size.width / 2f, size.height / 2f, r, paint)
    }
}

/** 검색 설정의 작은 칩. */
@Composable
private fun KindChip(label: String, on: Boolean, onClick: () -> Unit) {
    val p = palette
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(if (on) p.accent else p.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.xs),
    ) {
        Text(label, color = if (on) Color.White else p.textMuted, fontSize = Type.label)
    }
}

/** 트레이와 홈 고르기가 함께 쓰는 블록 줄. 견본은 캔버스와 같은 렌더러로 그린다. */
@Composable
private fun BlockChoices(
    defs: List<BlockDef>,
    onPick: (BlockDef) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 견본은 실제보다 조금 작게. LocalDensity 를 낮추면 크기·글자·그림자가 **함께** 줄어
    // 비율이 그대로 유지된다(따로 줄이면 모양이 망가진다).
    val base = LocalDensity.current
    val small = Density(base.density * PREVIEW_SCALE, base.fontScale)

    CompositionLocalProvider(LocalDensity provides small) {
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            defs.forEach { def ->
                BlockFace(
                    material = previewOf(def),
                    density = small.density,
                    onSocket = null, // 견본의 홈은 누르지 않는다
                    gestures = Modifier.clickable { onPick(def) },
                )
            }
        }
    }
}

/** 팔레트 견본의 크기 비율. 1 이면 캔버스와 같은 크기. */
private const val PREVIEW_SCALE = 0.8f

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
                onPick = onPick,
                modifier = Modifier.heightIn(max = 320.dp),
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
