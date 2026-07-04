package com.loopy.app.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 카드. 파스텔 라이트 테마에선 퓨어 화이트 + 부드러운 그림자(elevation)로 '공중에 뜬'
 * 클린 플랫 느낌을 낸다. 아주 얇은 차콜 테두리로 경계를 살짝 잡아준다.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier,
        color = LoopyCard,
        shape = shape,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, CardStroke),
    ) {
        Column(
            modifier = Modifier.padding(padding),
            content = content,
        )
    }
}
