package com.loopy.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.ui.theme.Depth
import com.loopy.app.ui.theme.Motion
import com.loopy.app.ui.theme.Radius
import com.loopy.app.ui.theme.Space
import com.loopy.app.ui.theme.Type
import com.loopy.app.ui.theme.neu
import com.loopy.app.ui.theme.palette
import com.loopy.app.ui.theme.tint

/**
 * 공용 컴포넌트.
 *
 * 화면이 색과 여백을 직접 정하지 않는다. 여기 있는 것들만 조합해 화면을 만들면 톤이 저절로
 * 맞고, 다크 모드나 색을 바꿀 때 손댈 곳이 한 군데로 모인다.
 */

/** 솟은 카드. */
@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    corner: Dp = Radius.lg,
    depth: Depth = Depth.MD,
    padding: Dp = Space.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .neu(corner = corner, depth = depth)
            .clip(RoundedCornerShape(corner))
            .padding(padding),
        content = content,
    )
}

@Composable
fun NeuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    enabled: Boolean = true,
    corner: Dp = Radius.md,
) {
    val p = palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = when {
        !enabled -> p.textMuted
        fill != null -> Color.White
        else -> p.textStrong
    }

    Box(
        modifier
            .neu(fill = fill, corner = corner, raised = !pressed)
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
        Text(text, color = fg, fontSize = Type.body, fontWeight = FontWeight.Medium)
    }
}

/** 정사각 아이콘 버튼. 선택되면 파인다. */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = 44.dp,
    corner: Dp = Radius.md,
    icon: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier
            .size(size)
            .neu(corner = corner, raised = !selected && !pressed)
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

/** 컬러 원형 버튼. 자기 색이 밖으로 번진다. */
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

    Box(
        modifier
            .size(size)
            .neu(fill = fill ?: p.accent, corner = size / 2, depth = Depth.LG, raised = !pressed)
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

/** 스위치. 파인 트랙 위에서 볼록한 노브가 미끄러진다. */
@Composable
fun NeuToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val trackW = 52.dp
    val trackH = 30.dp
    val knob = 24.dp
    val knobX by animateDpAsState(
        targetValue = if (checked) trackW - knob - 3.dp else 3.dp,
        animationSpec = Motion.snappy(),
        label = "knob",
    )

    Box(
        modifier
            .size(trackW, trackH)
            .neu(
                fill = if (checked) p.accent.copy(alpha = 0.25f) else null,
                corner = trackH / 2,
                depth = Depth.SM,
                raised = false,
            )
            .clip(RoundedCornerShape(trackH / 2))
            .toggleable(value = checked, onValueChange = onCheckedChange),
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
                .size(knob)
                .neu(
                    fill = if (checked) p.accent else null,
                    corner = knob / 2,
                    depth = Depth.SM,
                )
                .clip(CircleShape),
        )
    }
}

/** 리스트 행. 왼쪽에 정보, 오른쪽에 액션. */
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

    Row(
        modifier
            .fillMaxWidth()
            .neu(corner = Radius.md, depth = Depth.SM, raised = !(pressed && onClick != null))
            .clip(RoundedCornerShape(Radius.md))
            .let {
                if (onClick == null) it
                else it.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
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

/** 강조 텍스트. 그라데이션이 흐른다. */
@Composable
fun GradientText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = Type.heading,
    fontWeight: FontWeight = FontWeight.SemiBold,
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

/**
 * 빈 화면.
 *
 * 아무것도 없을 때가 사용자를 잃기 가장 쉬운 순간이다. 목록이 비었다고 알리는 대신
 * 무엇을 하면 되는지 보여준다.
 */
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
            fontSize = Type.title,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            description,
            color = p.textMuted,
            fontSize = Type.body,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Space.xl))
            action()
        }
    }
}
