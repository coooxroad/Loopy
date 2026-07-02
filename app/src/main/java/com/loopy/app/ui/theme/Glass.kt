package com.loopy.app.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 글래스 카드. Compose엔 backdrop blur가 기본이 없어서 M0에선 반투명 + 얇은 하이라이트
 * 테두리로 유리 문법을 만든다. 진짜 배경 블러는 나중에 Haze 라이브러리로 한 줄 업그레이드.
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
        color = GlassFill,
        shape = shape,
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(Color(0x33FFFFFF), Color(0x0DFFFFFF)),
                    ),
                    shape = shape,
                )
                .padding(padding),
            content = content,
        )
    }
}
