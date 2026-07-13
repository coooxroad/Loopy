package com.loopy.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp

/**
 * 뉴모피즘.
 *
 * 두 개의 그림자가 전부다. 좌상에 밝은 것, 우하에 어두운 것. 요소는 배경과 같은 색이고,
 * 면 안에는 아무것도 그리지 않는다. 안쪽에 그라데이션을 깔면 "카드 면에 그림자가 생긴"
 * 이상한 물건이 되고, 그 순간 배경에서 밀려 올라온 느낌이 사라진다.
 *
 * 누름은 두 그림자를 동시에 안쪽으로 뒤집는다. 이 반전이 이 스타일이 제대로 하는 유일한
 * 동작이다. 색만 바꾸면 눌린 느낌이 죽는다.
 *
 * 그림자는 요소 밖으로 뻗으므로 [neuPad] 만큼 여백이 확보되어 있어야 잘리지 않는다.
 */
fun Modifier.neu(
    corner: Dp = Radius.md,
    depth: Depth = Depth.MD,
    pressed: Boolean = false,
): Modifier = composed {
    val p = palette
    drawBehind {
        val off = depth.offset.toPx()
        val blur = depth.blur.toPx()
        val cr = corner.toPx()
        if (pressed) drawInset(p, cr, off, blur) else drawExtrude(p, cr, off, blur)
    }
}

/** 배경에서 솟아오른 표면. */
private fun DrawScope.drawExtrude(p: Palette, cr: Float, off: Float, blur: Float) {
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas

        val dark = android.graphics.Paint().apply {
            isAntiAlias = true
            color = p.surface.toArgb()
            setShadowLayer(blur, off, off, p.shadowColor.toArgb())
        }
        fw.drawRoundRect(rect, cr, cr, dark)

        val light = android.graphics.Paint().apply {
            isAntiAlias = true
            color = p.surface.toArgb()
            setShadowLayer(blur, -off, -off, p.light.toArgb())
        }
        fw.drawRoundRect(rect, cr, cr, light)
    }
    // 면은 균일해야 한다. 그라데이션 금지.
    drawRoundRect(p.surface, cornerRadius = CornerRadius(cr, cr))
}

/** 배경으로 눌려 들어간 표면. 그림자가 안쪽으로 뒤집힌다. */
private fun DrawScope.drawInset(p: Palette, cr: Float, off: Float, blur: Float) {
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
    drawRoundRect(p.surface, cornerRadius = CornerRadius(cr, cr))

    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val saved = fw.save()
        fw.clipPath(
            android.graphics.Path().apply {
                addRoundRect(rect, cr, cr, android.graphics.Path.Direction.CW)
            },
        )
        // 클립 안쪽에만 남도록, 요소보다 큰 사각형의 테두리에 그림자를 준다.
        val outer = android.graphics.RectF(
            -off * 2f, -off * 2f, size.width + off * 2f, size.height + off * 2f,
        )
        val strokeW = off * 2.5f

        val dark = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeW
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(blur, off, off, p.shadowColor.toArgb())
        }
        fw.drawRoundRect(outer, cr, cr, dark)

        val light = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeW
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(blur, -off, -off, p.light.toArgb())
        }
        fw.drawRoundRect(outer, cr, cr, light)

        fw.restoreToCount(saved)
    }
}

/**
 * 컬러 표면.
 *
 * 악센트 버튼처럼 배경과 다른 색인 요소. 뉴모피즘의 "같은 재질" 규칙에서 벗어나지만,
 * 그림자만으로는 무엇이 눌리는지 알 수 없기 때문에 필요하다. 접근성을 위해 인터랙티브
 * 요소에는 색이 있어야 한다.
 *
 * 그림자는 배경색에서 파생하고, 자기 색은 밖으로 은은히 번지게 한다. 예전에 자기 색으로
 * 그림자를 만든 뒤 본체를 덧칠했더니 경계 밖에서 흐렸다가 진해졌다 다시 흐려지는 이중 테가
 * 생겼다. 그림자가 요소 모양대로 깔리는데 그 위에 본체를 칠하면 안쪽 부분이 잘려나가기 때문이다.
 */
fun Modifier.neuColor(
    fill: Color,
    corner: Dp = Radius.md,
    depth: Depth = Depth.MD,
    pressed: Boolean = false,
): Modifier = composed {
    val p = palette
    drawBehind {
        val off = depth.offset.toPx()
        val blur = depth.blur.toPx()
        val cr = corner.toPx()
        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)

        if (!pressed) {
            // 자기 색 발광. 경계에서 가장 진하고 밖으로 옅어진다.
            val spread = blur * 1.4f
            val cx = size.width / 2f
            val cy = size.height / 2f + off * 0.4f
            val radius = maxOf(size.width, size.height) / 2f + spread
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to fill.copy(alpha = 0.30f),
                        0.6f to fill.copy(alpha = 0.24f),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }

        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val o = if (pressed) off * 0.4f else off
            val b = if (pressed) blur * 0.5f else blur

            val dark = android.graphics.Paint().apply {
                isAntiAlias = true
                color = fill.toArgb()
                setShadowLayer(b, o, o, p.shadowColor.toArgb())
            }
            fw.drawRoundRect(rect, cr, cr, dark)

            val light = android.graphics.Paint().apply {
                isAntiAlias = true
                color = fill.toArgb()
                setShadowLayer(b, -o, -o, p.light.toArgb())
            }
            fw.drawRoundRect(rect, cr, cr, light)
        }

        drawRoundRect(fill, cornerRadius = CornerRadius(cr, cr))
    }
}

/** 뉴모피즘 + 클립. */
fun Modifier.neuSurface(
    corner: Dp = Radius.md,
    depth: Depth = Depth.MD,
    pressed: Boolean = false,
): Modifier = this.neu(corner, depth, pressed).clip(RoundedCornerShape(corner))

fun Modifier.neuColorSurface(
    fill: Color,
    corner: Dp = Radius.md,
    depth: Depth = Depth.MD,
    pressed: Boolean = false,
): Modifier = this.neuColor(fill, corner, depth, pressed).clip(RoundedCornerShape(corner))
