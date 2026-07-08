package com.loopy.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 헤더 타이틀용 그라데이션(페리윙클→민트). */
val TitleBrush = Brush.linearGradient(listOf(GradA, GradB))

@Composable
fun GradientTitle(text: String, size: Int = 30, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(brush = TitleBrush, fontSize = size.sp, fontWeight = FontWeight.Bold),
    )
}

/**
 * 화면 바닥부터 약 40% 높이까지 은은하게 깔리는 애니메이션 그라데이션.
 * 색이 팔레트를 따라 천천히 순환한다. Box 안 맨 처음에 배치.
 */
@Composable
fun AnimatedBottomGradient(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "bg")
    val t by tr.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    val palette = listOf(
        GradA, GradB, Color(0xFFB9A7FF), Color(0xFFFFB4C6), GradA,
    )
    val idx = t * (palette.size - 1)
    val i = idx.toInt().coerceIn(0, palette.size - 2)
    val col = lerp(palette[i], palette[i + 1], idx - i)

    Canvas(modifier.fillMaxSize()) {
        val topY = size.height * 0.6f // 바닥에서 위로 40%
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, col.copy(alpha = 0.20f)),
                startY = topY, endY = size.height,
            ),
            topLeft = Offset(0f, topY),
            size = Size(size.width, size.height - topY),
        )
    }
}

/** 뉴모피즘 이중 그림자(좌상단 밝게 / 우하단 어둡게). */
private fun Modifier.neumorph(corner: Dp) = this.drawB
