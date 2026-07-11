package com.loopy.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 뉴모피즘 단일 구현.
 *
 * 광원은 왼쪽 위. 요소는 배경과 같은 재질이 솟거나(raised) 파인(recessed) 것처럼 보여야 하므로,
 * 그림자·글로우는 **요소 색이 아니라 표면(surface) 색**에서 파생된다.
 * 컬러 요소(fill)는 그 위에 자기 색 발광과 안쪽 그라데이션이 얹힌다.
 */
fun Modifier.neu(
    surface: Color = NeuBase,
    fill: Color? = null,
    corner: Dp = 12.dp,
    offset: Dp = 2.5.dp,
    blur: Dp = 5.dp,
    raised: Boolean = true,
): Modifier = this.drawBehind {
    val off = offset.toPx()
    val bl = blur.toPx()
    val cr = corner.toPx()
    val body = fill ?: surface
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)

    if (raised) {
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val shadow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = body.toArgb()
                setShadowLayer(bl, off, off, surface.shade(0.78f).toArgb())
            }
            fw.drawRoundRect(rect, cr, cr, shadow)

            val glow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = body.toArgb()
                setShadowLayer(bl, -off, -off, android.graphics.Color.WHITE)
            }
            fw.drawRoundRect(rect, cr, cr, glow)

            if (fill != null) {
                val tint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = body.toArgb()
                    setShadowLayer(bl * 1.05f, 0f, off * 0.4f, fill.copy(alpha = 0.45f).toArgb())
                }
                fw.drawRoundRect(rect, cr, cr, tint)
            }
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(body.tint(0.20f), body, body.shade(0.91f)),
            ),
            cornerRadius = CornerRadius(cr, cr),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.26f),
                0.32f to Color.Transparent,
            ),
            cornerRadius = CornerRadius(cr, cr),
        )
    } else {
        // 파임: 표면색으로 채운 뒤 안쪽에만 그림자를 그린다(clip 필수).
        drawRoundRect(body, cornerRadius = CornerRadius(cr, cr))
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val saved = fw.save()
            fw.clipPath(
                android.graphics.Path().apply {
                    addRoundRect(rect, cr, cr, android.graphics.Path.Direction.CW)
                },
            )
            val outer = android.graphics.RectF(
                -off * 2f, -off * 2f, size.width + off * 2f, size.height + off * 2f,
            )
            val inner = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = off * 2.2f
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(bl, off, off, surface.shade(0.72f).toArgb())
            }
            fw.drawRoundRect(outer, cr, cr, inner)

            val reflect = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = off * 2.2f
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(bl, -off, -off, android.graphics.Color.WHITE)
            }
            fw.drawRoundRect(outer, cr, cr, reflect)
            fw.restoreToCount(saved)
        }
    }
}

/**
 * 가로로 꽉 찬 바. 좌우 여백이 없어 대각선 그림자를 줄 자리가 없으므로 수직 방향만 쓴다.
 * 위/아래 층 구분이 목적이라 뉴모피즘 카드와는 의도가 다르다.
 */
fun Modifier.neuBar(surface: Color = NeuBase): Modifier = this.drawBehind {
    val off = 3.dp.toPx()
    val bl = 7.dp.toPx()
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val shadow = android.graphics.Paint().apply {
            isAntiAlias = true
            color = surface.toArgb()
            setShadowLayer(bl, 0f, off, surface.shade(0.90f).toArgb())
        }
        fw.drawRect(rect, shadow)
        val glow = android.graphics.Paint().apply {
            isAntiAlias = true
            color = surface.toArgb()
            setShadowLayer(bl * 0.7f, 0f, -off * 0.7f, 0x66FFFFFF)
        }
        fw.drawRect(rect, glow)
    }
}

/** 뉴모피즘 + 클립 + 배경을 한 번에. 대부분의 호출부는 이걸 쓰면 된다. */
fun Modifier.neuSurface(
    surface: Color = NeuBase,
    fill: Color? = null,
    corner: Dp = 12.dp,
    offset: Dp = 2.5.dp,
    blur: Dp = 5.dp,
    raised: Boolean = true,
): Modifier = this
    .neu(surface, fill, corner, offset, blur, raised)
    .clip(RoundedCornerShape(corner))
    .background(if (raised) (fill ?: surface) else Color.Transparent)

fun Color.shade(f: Float) = Color(red * f, green * f, blue * f, alpha)

fun Color.tint(f: Float) =
    Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)
