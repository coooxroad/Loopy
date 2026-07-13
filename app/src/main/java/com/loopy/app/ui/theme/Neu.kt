package com.loopy.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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
 * 뉴모피즘.
 *
 * 광원은 왼쪽 위. 요소는 배경과 같은 재질이 솟거나 파인 것처럼 보여야 하므로, 그림자와 글로우는
 * 요소 색이 아니라 표면색에서 파생된다. 팔레트만 갈면 다크 모드가 같은 코드로 동작하는 이유다.
 *
 * 그리는 순서가 중요하다. 예전에는 컬러 요소에 setShadowLayer 로 자기 색 글로우를 준 뒤 본체를
 * 다시 칠했는데, 그러면 경계 바깥에서 흐렸다가 진해졌다 다시 흐려지는 이중 테가 생겼다.
 * 그림자는 요소 모양대로 깔리는데 그 위에 본체를 덧칠하면 안쪽 그림자가 잘려나가기 때문이다.
 *
 * 그래서 지금은 이렇게 그린다.
 *  1. 컬러 글로우 — 요소 뒤에 방사형으로. 경계에서 시작해 밖으로만 옅어진다.
 *  2. 표면 그림자와 글로우 — 요소 밖으로.
 *  3. 본체 — 불투명하게 한 번만.
 */
fun Modifier.neu(
    fill: Color? = null,
    corner: Dp = Radius.md,
    depth: Depth = Depth.MD,
    raised: Boolean = true,
): Modifier = composed {
    val p = palette
    val surface = p.surface
    val body = fill ?: surface

    drawBehind {
        val off = depth.offset.toPx()
        val blur = depth.blur.toPx()
        val cr = corner.toPx()
        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)

        if (!raised) {
            drawRecessed(rect, cr, off, blur, body, surface, p)
            return@drawBehind
        }

        // 1) 컬러 요소면 자기 색이 밖으로 번진다. 경계가 가장 진하고 멀어질수록 옅어져야 한다.
        if (fill != null) {
            val spread = blur * 1.6f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = maxOf(size.width, size.height) / 2f + spread
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to fill.copy(alpha = 0.35f),
                        0.62f to fill.copy(alpha = 0.28f),
                        1.0f to Color.Transparent,
                    ),
                    center = androidx.compose.ui.geometry.Offset(cx, cy + off * 0.5f),
                    radius = radius,
                ),
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(cx, cy + off * 0.5f),
            )
        }

        // 2) 표면에서 파생된 그림자와 글로우. 요소 밖에만 남는다.
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val shadow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = body.toArgb()
                setShadowLayer(blur, off, off, p.shadow.toArgb())
            }
            fw.drawRoundRect(rect, cr, cr, shadow)

            val glow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = body.toArgb()
                setShadowLayer(blur, -off, -off, p.glow.toArgb())
            }
            fw.drawRoundRect(rect, cr, cr, glow)
        }

        // 3) 본체. 위가 밝고 아래가 어두워야 솟아 보인다.
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(body.tint(0.16f), body, body.shade(0.92f)),
            ),
            cornerRadius = CornerRadius(cr, cr),
        )
    }
}

/**
 * 파인 표면.
 *
 * 그림자를 밖에 뿌리면 솟아 보인다. 파이려면 안쪽에 그려야 하므로 클립이 필수다.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRecessed(
    rect: android.graphics.RectF,
    cr: Float,
    off: Float,
    blur: Float,
    body: Color,
    surface: Color,
    p: Palette,
) {
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
            -off * 2f, -off * 2f, rect.right + off * 2f, rect.bottom + off * 2f,
        )
        val inner = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = off * 2.2f
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(blur, off, off, p.shadow.toArgb())
        }
        fw.drawRoundRect(outer, cr, cr, inner)

        val reflect = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = off * 2.2f
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(blur, -off, -off, p.glow.toArgb())
        }
        fw.drawRoundRect(outer, cr, cr, reflect)
        fw.restoreToCount(saved)
    }
}

/** 뉴모피즘 + 클립 + 배경. 대부분의 호출부는 이걸 쓴다. */
@Composable
fun Modifier.neuSurface(
    fill: Color? = null,
    corner: Dp = Radius.md,
    depth: Depth = Depth.MD,
    raised: Boolean = true,
): Modifier {
    val p = palette
    return this
        .neu(fill, corner, depth, raised)
        .clip(RoundedCornerShape(corner))
        .background(if (raised) Color.Transparent else (fill ?: p.sunken))
}

/**
 * 가로로 꽉 찬 바.
 *
 * 좌우 여백이 없어 대각선 그림자를 줄 자리가 없다. 위아래 층을 나누는 것이 목적이므로
 * 수직 방향만 쓴다.
 */
fun Modifier.neuBar(): Modifier = composed {
    val p = palette
    drawBehind {
        val off = 3.dp.toPx()
        val blur = 7.dp.toPx()
        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val shadow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = p.surface.toArgb()
                setShadowLayer(blur, 0f, off, p.shadow.copy(alpha = 0.6f).toArgb())
            }
            fw.drawRect(rect, shadow)
            val glow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = p.surface.toArgb()
                setShadowLayer(blur * 0.7f, 0f, -off * 0.7f, p.glow.copy(alpha = 0.5f).toArgb())
            }
            fw.drawRect(rect, glow)
        }
    }
}
