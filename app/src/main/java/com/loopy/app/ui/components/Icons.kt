package com.loopy.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 아이콘.
 *
 * 이모지를 쓰면 기기와 OS 버전마다 모양이 달라지고, 뉴모피즘의 차분한 톤과 어긋난다.
 * 직접 그리면 굵기와 여백을 앱 전체에서 통제할 수 있다.
 *
 * 모든 아이콘은 정사각 안에 0~1 비율로 그린다. 크기를 바꿔도 모양이 유지된다.
 */
enum class Icon {
    DASHBOARD,
    LIBRARY,
    SETTINGS,
    PLAY,
    PAUSE,
    STOP,
    RECORD,
    ADD,
    CLOSE,
    BACK,
    EDIT,
    DELETE,
    MORE,
    MOON,
    VIDEO,
    LIST,
    STAR,
    STAR_FILLED,
    FOLDER,
    UNDO,
    REDO,
    SPLIT,
    TRIM_LEFT,
    TRIM_RIGHT,
    CHECK,
}

@Composable
fun LoopyIcon(
    icon: Icon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Float = 0.09f,
) {
    Canvas(modifier.size(size)) {
        drawIcon(icon, tint, strokeWidth)
    }
}

private fun DrawScope.drawIcon(icon: Icon, c: Color, sw: Float) {
    val w = size.width
    val s = w * sw
    val stroke = Stroke(width = s, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = s) =
        drawLine(c, Offset(w * x1, w * y1), Offset(w * x2, w * y2), width, StrokeCap.Round)

    fun rect(x: Float, y: Float, rw: Float, rh: Float, r: Float = 0.08f, filled: Boolean = false) =
        drawRoundRect(
            c,
            topLeft = Offset(w * x, w * y),
            size = Size(w * rw, w * rh),
            cornerRadius = CornerRadius(w * r, w * r),
            style = if (filled) Fill else stroke,
        )

    fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false) =
        drawCircle(
            c,
            radius = w * r,
            center = Offset(w * cx, w * cy),
            style = if (filled) Fill else stroke,
        )

    when (icon) {
        Icon.DASHBOARD -> {
            rect(0.14f, 0.14f, 0.3f, 0.3f)
            rect(0.56f, 0.14f, 0.3f, 0.3f)
            rect(0.14f, 0.56f, 0.3f, 0.3f)
            rect(0.56f, 0.56f, 0.3f, 0.3f)
        }

        Icon.LIBRARY -> {
            rect(0.14f, 0.2f, 0.72f, 0.6f, 0.1f)
            line(0.14f, 0.4f, 0.86f, 0.4f)
            line(0.38f, 0.4f, 0.38f, 0.8f)
        }

        Icon.SETTINGS -> {
            circle(0.5f, 0.5f, 0.17f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val x = 0.5f + 0.32f * Math.cos(a).toFloat()
                val y = 0.5f + 0.32f * Math.sin(a).toFloat()
                val x2 = 0.5f + 0.22f * Math.cos(a).toFloat()
                val y2 = 0.5f + 0.22f * Math.sin(a).toFloat()
                line(x2, y2, x, y, s * 0.85f)
            }
        }

        Icon.PLAY -> drawPath(
            Path().apply {
                moveTo(w * 0.28f, w * 0.18f)
                lineTo(w * 0.82f, w * 0.5f)
                lineTo(w * 0.28f, w * 0.82f)
                close()
            },
            c,
        )

        Icon.PAUSE -> {
            rect(0.28f, 0.18f, 0.15f, 0.64f, 0.05f, filled = true)
            rect(0.57f, 0.18f, 0.15f, 0.64f, 0.05f, filled = true)
        }

        Icon.STOP -> rect(0.25f, 0.25f, 0.5f, 0.5f, 0.08f, filled = true)

        Icon.RECORD -> circle(0.5f, 0.5f, 0.28f, filled = true)

        Icon.ADD -> {
            line(0.5f, 0.2f, 0.5f, 0.8f)
            line(0.2f, 0.5f, 0.8f, 0.5f)
        }

        Icon.CLOSE -> {
            line(0.25f, 0.25f, 0.75f, 0.75f)
            line(0.75f, 0.25f, 0.25f, 0.75f)
        }

        Icon.BACK -> {
            line(0.6f, 0.22f, 0.34f, 0.5f)
            line(0.34f, 0.5f, 0.6f, 0.78f)
        }

        Icon.EDIT -> {
            drawPath(
                Path().apply {
                    moveTo(w * 0.2f, w * 0.72f)
                    lineTo(w * 0.24f, w * 0.58f)
                    lineTo(w * 0.66f, w * 0.16f)
                    lineTo(w * 0.82f, w * 0.32f)
                    lineTo(w * 0.4f, w * 0.74f)
                    close()
                },
                c,
                style = stroke,
            )
            line(0.2f, 0.84f, 0.5f, 0.84f)
        }

        Icon.DELETE -> {
            line(0.18f, 0.28f, 0.82f, 0.28f)
            line(0.38f, 0.28f, 0.38f, 0.18f)
            line(0.38f, 0.18f, 0.62f, 0.18f)
            line(0.62f, 0.18f, 0.62f, 0.28f)
            rect(0.26f, 0.34f, 0.48f, 0.48f, 0.06f)
            line(0.42f, 0.44f, 0.42f, 0.72f, s * 0.8f)
            line(0.58f, 0.44f, 0.58f, 0.72f, s * 0.8f)
        }

        Icon.MORE -> {
            circle(0.5f, 0.22f, 0.06f, filled = true)
            circle(0.5f, 0.5f, 0.06f, filled = true)
            circle(0.5f, 0.78f, 0.06f, filled = true)
        }

        Icon.MOON -> drawPath(
            Path().apply {
                moveTo(w * 0.72f, w * 0.66f)
                cubicTo(w * 0.5f, w * 0.72f, w * 0.28f, w * 0.56f, w * 0.32f, w * 0.34f)
                cubicTo(w * 0.34f, w * 0.24f, w * 0.42f, w * 0.17f, w * 0.5f, w * 0.15f)
                cubicTo(w * 0.36f, w * 0.34f, w * 0.46f, w * 0.62f, w * 0.72f, w * 0.66f)
                close()
            },
            c,
        )

        Icon.VIDEO -> {
            rect(0.14f, 0.28f, 0.48f, 0.44f, 0.07f)
            drawPath(
                Path().apply {
                    moveTo(w * 0.66f, w * 0.44f)
                    lineTo(w * 0.86f, w * 0.3f)
                    lineTo(w * 0.86f, w * 0.7f)
                    lineTo(w * 0.66f, w * 0.56f)
                    close()
                },
                c,
            )
        }

        Icon.LIST -> {
            line(0.2f, 0.28f, 0.8f, 0.28f)
            line(0.2f, 0.5f, 0.8f, 0.5f)
            line(0.2f, 0.72f, 0.8f, 0.72f)
        }

        Icon.STAR, Icon.STAR_FILLED -> {
            val path = Path()
            for (i in 0 until 5) {
                val outer = Math.toRadians(-90.0 + i * 72.0)
                val inner = Math.toRadians(-90.0 + i * 72.0 + 36.0)
                val ox = 0.5f + 0.34f * Math.cos(outer).toFloat()
                val oy = 0.5f + 0.34f * Math.sin(outer).toFloat()
                val ix = 0.5f + 0.15f * Math.cos(inner).toFloat()
                val iy = 0.5f + 0.15f * Math.sin(inner).toFloat()
                if (i == 0) path.moveTo(w * ox, w * oy) else path.lineTo(w * ox, w * oy)
                path.lineTo(w * ix, w * iy)
            }
            path.close()
            drawPath(path, c, style = if (icon == Icon.STAR_FILLED) Fill else stroke)
        }

        Icon.FOLDER -> drawPath(
            Path().apply {
                moveTo(w * 0.14f, w * 0.76f)
                lineTo(w * 0.14f, w * 0.26f)
                lineTo(w * 0.42f, w * 0.26f)
                lineTo(w * 0.5f, w * 0.36f)
                lineTo(w * 0.86f, w * 0.36f)
                lineTo(w * 0.86f, w * 0.76f)
                close()
            },
            c,
            style = stroke,
        )

        Icon.UNDO, Icon.REDO -> {
            val flip = icon == Icon.REDO
            fun fx(x: Float) = if (flip) 1f - x else x
            drawArc(
                c,
                startAngle = if (flip) -30f else 210f,
                sweepAngle = if (flip) -240f else 240f,
                useCenter = false,
                topLeft = Offset(w * 0.22f, w * 0.26f),
                size = Size(w * 0.56f, w * 0.56f),
                style = stroke,
            )
            line(fx(0.22f), 0.3f, fx(0.22f), 0.5f)
            line(fx(0.22f), 0.3f, fx(0.42f), 0.3f)
        }

        Icon.SPLIT -> {
            rect(0.1f, 0.34f, 0.28f, 0.32f, 0.05f, filled = true)
            rect(0.62f, 0.34f, 0.28f, 0.32f, 0.05f, filled = true)
            line(0.5f, 0.14f, 0.5f, 0.86f, s * 0.9f)
        }

        Icon.TRIM_LEFT, Icon.TRIM_RIGHT -> {
            val left = icon == Icon.TRIM_LEFT
            val barX = if (left) 0.6f else 0.4f
            line(barX, 0.16f, barX, 0.84f)
            val bx = if (left) barX + 0.08f else 0.16f
            val bw = if (left) 0.86f - bx else barX - 0.08f - 0.16f
            rect(bx, 0.34f, bw, 0.32f, 0.05f, filled = true)
        }

        Icon.CHECK -> {
            line(0.22f, 0.52f, 0.42f, 0.72f)
            line(0.42f, 0.72f, 0.78f, 0.28f)
        }
    }
}
