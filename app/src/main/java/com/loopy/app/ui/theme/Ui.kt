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
private fun Modifier.neumorph(corner: Dp) = this.drawBehind {
    val r = corner.toPx()
    val off = 5.dp.toPx()
    val blur = 11.dp.toPx()
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
        val dark = android.graphics.Paint().apply {
            isAntiAlias = true
            color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, off, off, 0xFFC9D0E0.toInt())
        }
        fw.drawRoundRect(rect, r, r, dark)
        val light = android.graphics.Paint().apply {
            isAntiAlias = true
            color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, -off, -off, 0xFFFFFFFF.toInt())
        }
        fw.drawRoundRect(rect, r, r, light)
    }
}

/** 정통 뉴모피즘 카드 — 배경과 같은 톤 + 이중 그림자로 은은하게 떠 보인다. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .neumorph(cornerRadius)
            .clip(shape)
            .background(NeuBase)
            .padding(padding),
    ) {
        Column(content = content)
    }
}

/** 아주 얇은 라인 아이콘. kind: home / playlist / library / settings. */
@Composable
fun LineIcon(kind: String, color: Color, size: Dp = 24.dp, strokeWidth: Float = 2f) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        fun p(x: Float, y: Float) = Offset(x / 24f * s, y / 24f * s)
        val stroke = Stroke(width = strokeWidth / 24f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "home" -> {
                drawPath(Path().apply {
                    moveTo(p(3f, 11f).x, p(3f, 11f).y)
                    lineTo(p(12f, 3.5f).x, p(12f, 3.5f).y)
                    lineTo(p(21f, 11f).x, p(21f, 11f).y)
                }, color, style = stroke)
                drawPath(Path().apply {
                    moveTo(p(5.5f, 9.5f).x, p(5.5f, 9.5f).y)
                    lineTo(p(5.5f, 20f).x, p(5.5f, 20f).y)
                    lineTo(p(18.5f, 20f).x, p(18.5f, 20f).y)
                    lineTo(p(18.5f, 9.5f).x, p(18.5f, 9.5f).y)
                }, color, style = stroke)
                drawPath(Path().apply {
                    moveTo(p(10f, 20f).x, p(10f, 20f).y)
                    lineTo(p(10f, 14f).x, p(10f, 14f).y)
                    lineTo(p(14f, 14f).x, p(14f, 14f).y)
                    lineTo(p(14f, 20f).x, p(14f, 20f).y)
                }, color, style = stroke)
            }
            "playlist" -> {
                drawLine(color, p(4f, 7f), p(15f, 7f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 12f), p(15f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 17f), p(11f, 17f), stroke.width, StrokeCap.Round)
                drawLine(color, p(19f, 6f), p(19f, 16f), stroke.width, StrokeCap.Round)
                drawCircle(color, p(2.2f, 0f).x - p(0f, 0f).x, p(17f, 16.5f), style = stroke)
            }
            "library" -> {
                drawPath(Path().apply {
                    moveTo(p(3.5f, 7f).x, p(3.5f, 7f).y)
                    lineTo(p(9f, 7f).x, p(9f, 7f).y)
                    lineTo(p(11f, 9f).x, p(11f, 9f).y)
                    lineTo(p(20.5f, 9f).x, p(20.5f, 9f).y)
                    lineTo(p(20.5f, 18.5f).x, p(20.5f, 18.5f).y)
                    lineTo(p(3.5f, 18.5f).x, p(3.5f, 18.5f).y)
                    close()
                }, color, style = stroke)
            }
            "settings" -> {
                drawLine(color, p(4f, 7f), p(20f, 7f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 12f), p(20f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 17f), p(20f, 17f), stroke.width, StrokeCap.Round)
                val r = p(2.6f, 0f).x - p(0f, 0f).x
                drawCircle(NeuBase, r, p(15f, 7f)); drawCircle(color, r, p(15f, 7f), style = stroke)
                drawCircle(NeuBase, r, p(9f, 12f)); drawCircle(color, r, p(9f, 12f), style = stroke)
                drawCircle(NeuBase, r, p(16f, 17f)); drawCircle(color, r, p(16f, 17f), style = stroke)
            }
        }
    }
}
