package com.loopy.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.loopy.app.ui.theme.Depth
import com.loopy.app.ui.theme.Motion
import com.loopy.app.ui.theme.Radius
import com.loopy.app.ui.theme.Space
import com.loopy.app.ui.theme.Type
import com.loopy.app.ui.theme.neu
import com.loopy.app.ui.theme.neuColor
import com.loopy.app.ui.theme.palette

/**
 * 공용 컴포넌트.
 *
 * 뉴모피즘의 그림자는 요소 밖으로 뻗는다. 여백이 없으면 잘려서 뭉툭해 보이므로, 각 컴포넌트가
 * 자기 그림자 자리를 스스로 확보한다. 화면이 그걸 챙기게 두면 반드시 잊는다.
 *
 * 그리고 그림자만으로는 무엇이 눌리는지 알 수 없다. 인터랙티브 요소에는 악센트 색이나
 * 테두리 같은 그림자 아닌 단서가 반드시 있어야 한다.
 */

/** 그림자가 뻗을 자리. 깊이에 맞춰 잡는다. */
@Composable
fun shadowPad(depth: Depth = Depth.MD): Dp = depth.offset + depth.blur / 2

/** 카드. 정보를 담는 표면. 누를 수 없으므로 악센트가 없다. */
@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    corner: Dp = Radius.lg,
    depth: Depth = Depth.MD,
    padding: Dp = Space.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.padding(shadowPad(depth))) {
        Column(
            Modifier
                .fillMaxWidth()
                .neu(corner = corner, depth = depth)
                .clip(RoundedCornerShape(corner))
                .padding(padding),
            content = content,
        )
    }
}

/**
 * 주 버튼.
 *
 * 악센트 색으로 채운다. 배경과 같은 색이면 사용자가 버튼임을 알 수 없다. 뉴모피즘이
 * 접근성으로 비판받는 지점이 정확히 이것이고, 색으로 해결한다.
 */
@Composable
fun NeuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    corner: Dp = Radius.md,
    depth: Depth = Depth.MD,
) {
    val p = palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (enabled) p.accent else p.accentSoft

    Box(modifier.padding(shadowPad(depth))) {
        Box(
            Modifier
                .fillMaxWidth()
                .neuColor(fill = fill, corner = corner, depth = depth, pressed = pressed)
                .clip(RoundedCornerShape(corner))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = Space.lg, vertical = Space.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = Color.White, fontSize = Type.body, fontWeight = FontWeight.Medium)
        }
    }
}

/** 보조 버튼. 표면색이지만 악센트 테두리로 누를 수 있음을 알린다. */
@Composable
fun NeuOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    corner: Dp = Radius.md,
    depth: Depth = Depth.SM,
) {
    val p = palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tint = if (enabled) p.accent else p.textMuted

    Box(modifier.padding(shadowPad(depth))) {
        Box(
            Modifier
                .fillMaxWidth()
                .neu(corner = corner, depth = depth, pressed = pressed)
                .clip(RoundedCornerShape(corner))
                .border(1.5.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(corner))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = Space.lg, vertical = Space.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = tint, fontSize = Type.body, fontWeight = FontWeight.Medium)
        }
    }
}

/** 아이콘 버튼. 선택되면 눌린 상태로 남는다. */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = 48.dp,
    corner: Dp = Radius.sm,
    depth: Depth = Depth.SM,
    icon: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(modifier.padding(shadowPad(depth))) {
        Box(
            Modifier
                .size(size)
                .neu(corner = corner, depth = depth, pressed = selected || pressed)
                .clip(RoundedCornerShape(corner))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
            content = { icon() },
        )
    }
}

/** 떠 있는 원형 버튼. */
@Composable
fun NeuFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    fill: Color? = null,
    icon: @Composable () -> Unit,
) {
    val p = palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(modifier.padding(shadowPad(Depth.LG))) {
        Box(
            Modifier
                .size(size)
                .neuColor(
                    fill = fill ?: p.accent,
                    corner = size / 2,
                    depth = Depth.LG,
                    pressed = pressed,
                )
                .clip(CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
            content = { icon() },
        )
    }
}

/**
 * 스위치.
 *
 * 트랙은 눌린 홈, 노브는 솟은 것. 켜지면 노브가 악센트 색이 된다. 색이 상태를 말해야 한다.
 * 그림자만으로 켜짐과 꺼짐을 구분하게 두면 아무도 알아채지 못한다.
 */
@Composable
fun NeuToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val trackW = 56.dp
    val trackH = 32.dp
    val knob = 24.dp
    val knobX by animateDpAsState(
        targetValue = if (checked) trackW - knob - 4.dp else 4.dp,
        animationSpec = Motion.snappy(),
        label = "knob",
    )

    Box(modifier.padding(shadowPad(Depth.SM))) {
        Box(
            Modifier
                .size(trackW, trackH)
                .neu(corner = trackH / 2, depth = Depth.SM, pressed = true)
                .clip(RoundedCornerShape(trackH / 2))
                .toggleable(value = checked, onValueChange = onCheckedChange),
        ) {
            Box(
                Modifier
                    .offset(x = knobX, y = 4.dp)
                    .size(knob)
                    .let {
                        if (checked) {
                            it.neuColor(fill = p.accent, corner = knob / 2, depth = Depth.SM)
                        } else {
                            it.neu(corner = knob / 2, depth = Depth.SM)
                        }
                    }
                    .clip(CircleShape),
            )
        }
    }
}

/** 리스트 행. */
@Composable
fun NeuListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth = Depth.SM

    Box(modifier.padding(shadowPad(depth))) {
        Row(
            Modifier
                .fillMaxWidth()
                .neu(
                    corner = Radius.sm,
                    depth = depth,
                    pressed = pressed && onClick != null,
                )
                .clip(RoundedCornerShape(Radius.sm))
                .let {
                    if (onClick == null) {
                        it
                    } else {
                        it.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                    }
                }
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(Space.md))
            }
            Column(Modifier.weight(1f), content = content)
            if (trailing != null) {
                Spacer(Modifier.width(Space.md))
                trailing()
            }
        }
    }
}

/** 눌린 홈. 입력 필드처럼 무언가를 담는 자리. */
@Composable
fun NeuWell(
    modifier: Modifier = Modifier,
    corner: Dp = Radius.sm,
    padding: Dp = Space.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .neu(corner = corner, depth = Depth.SM, pressed = true)
            .clip(RoundedCornerShape(corner))
            .padding(padding),
        content = content,
    )
}

@Composable
fun GradientText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = Type.heading,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    val p = palette
    Text(
        text,
        modifier = modifier,
        style = TextStyle(
            brush = Brush.horizontalGradient(listOf(p.gradientStart, p.gradientEnd)),
            fontSize = fontSize,
            fontWeight = fontWeight,
        ),
    )
}

/** 아무것도 없을 때. 목록이 비었다고 알리는 대신 무엇을 하면 되는지 보여준다. */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val p = palette
    Column(
        modifier.fillMaxWidth().padding(Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            color = p.textStrong,
            fontSize = Type.heading,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            description,
            color = p.textMuted,
            fontSize = Type.caption,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Space.xl))
            action()
        }
    }
}
