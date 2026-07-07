package com.loopy.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 헤더 타이틀용 그라데이션(페리윙클→민트). */
val TitleBrush = Brush.linearGradient(listOf(GradA, GradB))

/** 그라데이션 텍스트 타이틀. */
@Composable
fun GradientTitle(text: String, size: Int = 30, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(brush = TitleBrush, fontSize = size.sp, fontWeight = FontWeight.Bold),
    )
}

/**
 * 아주 얇은 라인 아이콘(직접 path). kind: home / playlist / library / settings / loop.
 */
@Composable
fun LineIcon(kind: String, color: Color, size: Dp = 24.dp, strokeWidth: Float = 2f) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        fun p(x: Float, y: Float) = Offset(x / 24f * s, y / 24f * s)
        val stroke = Stroke(
            width = strokeWidth / 24f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (kind) {
            "home" -> {
                val roof = Path().apply {
                    moveTo(p(3f, 11f).x, p(3f, 11f).y)
                    lineTo(p(12f, 3.5f).x, p(12f, 3.5f).y)
                    lineTo(p(21f, 11f).x, p(21f, 11f).y)
                }
                drawPath(roof, color, style = stroke)
                val body = Path().apply {
                    moveTo(p(5.5f, 9.5f).x, p(5.5f, 9.5f).y)
                    lineTo(p(5.5f, 20f).x, p(5.5f, 20f).y)
                    lineTo(p(18.5f, 20f).x, p(18.5f, 20f).y)
                    lineTo(p(18.5f, 9.5f).x, p(18.5f, 9.5f).y)
                }
                drawPath(body, color, style = stroke)
                // 문
                val door = Path().apply {
                    moveTo(p(10f, 20f).x, p(10f, 20f).y)
                    lineTo(p(10f, 14f).x, p(10f, 14f).y)
                    lineTo(p(14f, 14f).x, p(14f, 14f).y)
                    lineTo(p(14f, 20f).x, p(14f, 20f).y)
                }
                drawPath(door, color, style = stroke)
            }
            "playlist" -> {
                // 재생 목록: 세 줄 + 음표
                drawLine(color, p(4f, 7f), p(15f, 7f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 12f), p(15f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 17f), p(11f, 17f), stroke.width, StrokeCap.Round)
                // 음표 stem + head
                drawLine(color, p(19f, 6f), p(19f, 16f), stroke.width, StrokeCap.Round)
                drawCircle(color, radius = p(2.2f, 0f).x - p(0f, 0f).x, center = p(17f, 16.5f), style = stroke)
            }
            "library" -> {
                // 폴더
                val folder = Path().apply {
                    moveTo(p(3.5f, 7f).x, p(3.5f, 7f).y)
                    lineTo(p(9f, 7f).x, p(9f, 7f).y)
                    lineTo(p(11f, 9f).x, p(11f, 9f).y)
                    lineTo(p(20.5f, 9f).x, p(20.5f, 9f).y)
                    lineTo(p(20.5f, 18.5f).x, p(20.5f, 18.5f).y)
                    lineTo(p(3.5f, 18.5f).x, p(3.5f, 18.5f).y)
                    close()
                }
                drawPath(folder, color, style = stroke)
            }
            "settings" -> {
                // 슬라이더 3줄 + 노브
                drawLine(color, p(4f, 7f), p(20f, 7f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 12f), p(20f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 17f), p(20f, 17f), stroke.width, StrokeCap.Round)
                val r = p(2.6f, 0f).x - p(0f, 0f).x
                drawCircle(LoopyWhite, r, p(15f, 7f))
                drawCircle(color, r, p(15f, 7f), style = stroke)
                drawCircle(LoopyWhite, r, p(9f, 12f))
                drawCircle(color, r, p(9f, 12f), style = stroke)
                drawCircle(LoopyWhite, r, p(16f, 17f))
                drawCircle(color, r, p(16f, 17f), style = stroke)
            }
            "loop" -> {
                // 순환 화살표(로고)
                val arc = Path().apply {
                    addArc(
                        androidx.compose.ui.geometry.Rect(p(5f, 5f), p(19f, 19f)),
                        -40f, 300f,
                    )
                }
                drawPath(arc, color, style = stroke)
                // 화살촉
                val head = Path().apply {
                    moveTo(p(15.5f, 4.5f).x, p(15.5f, 4.5f).y)
                    lineTo(p(18.5f, 7f).x, p(18.5f, 7f).y)
                    lineTo(p(15f, 8.5f).x, p(15f, 8.5f).y)
                }
                drawPath(head, color, style = stroke)
            }
        }
    }
}

/**
 * 순백 리디자인 카드 — 미묘한 상단 광채(subtle glow) + 얇은 테두리.
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier,
        shape = shape,
        color = LoopyWhite,
        shadowElevation = 3.dp,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, CardStroke),
    ) {
        Column(
            Modifier
                .background(
                    Brush.verticalGradient(
                        0f to LoopySubtle, 0.35f to LoopyWhite,
                    )
                )
                .padding(padding),
            content = content,
        )
    }
}
