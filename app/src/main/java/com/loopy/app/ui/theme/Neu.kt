package com.loopy.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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

/**
 * 배경에서 솟아오른 표면.
 *
 * 바깥 그림자만으로는 경계가 흐릿하게 번지기만 한다. 잘 만들어진 뉴모피즘은 경계선 안쪽에
 * 바깥과 반대되는 아주 옅은 선을 하나 더 둔다. 그림자가 지는 쪽 안에는 미세한 밝음을,
 * 빛이 닿는 쪽 안에는 미세한 어둠을. 경계에서 밝음과 어둠이 교차하면서 형태가 또렷해진다.
 *
 * 이 선은 반드시 희미해야 한다. 조금이라도 진하면 테두리를 두른 것처럼 보여 재질감이 죽는다.
 */
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

    // 안쪽 미세 대비선. 바깥 그림자(우하) 안쪽은 옅게 밝고, 바깥 글로우(좌상) 안쪽은 옅게 어둡다.
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val saved = fw.save()
        fw.clipPath(
            android.graphics.Path().apply {
                addRoundRect(rect, cr, cr, android.graphics.Path.Direction.CW)
            },
        )
        val hair = off * 0.5f
        val hairBlur = blur * 0.35f
        val outer = android.graphics.RectF(
            -hair * 2f, -hair * 2f, size.width + hair * 2f, size.height + hair * 2f,
        )

        val innerLight = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = hair * 2f
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(hairBlur, hair, hair, p.light.copy(alpha = 0.22f).toArgb())
        }
        fw.drawRoundRect(outer, cr, cr, innerLight)

        val innerDark = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = hair * 2f
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(hairBlur, -hair, -hair, p.shadowColor.copy(alpha = 0.18f).toArgb())
        }
        fw.drawRoundRect(outer, cr, cr, innerDark)

        fw.restoreToCount(saved)
    }
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
 * 악센트 버튼처럼 배경과 다른 색인 요소. 그림자만으로는 무엇이 눌리는지 알 수 없으므로
 * 인터랙티브 요소에는 색이 필요하다.
 *
 * 자기 색이 주변으로 번져야 한다. 잘 만들어진 뉴모피즘의 컬러 버튼은 색이 몽환적으로
 * 퍼지면서도 버튼 자체는 또렷하다. 번짐이 없으면 스티커처럼 얹힌 것 같고, 너무 넓으면
 * 버튼과 분리되어 따로 논다.
 *
 * 번짐은 setShadowLayer 로 그린다. 방사형 원으로 그리면 둥근 사각형 버튼과 모양이 어긋나
 * 모서리에서 삐져나온다. 그림자는 요소 모양을 그대로 따라가므로 그런 어긋남이 없다.
 *
 * 예전에는 자기 색으로 그림자를 만든 뒤 본체를 덧칠했더니 경계 밖에서 흐렸다가 진해졌다
 * 다시 흐려지는 이중 테가 생겼다. 그림자가 요소 모양대로 깔리는데 그 위에 본체를 칠하면
 * 안쪽이 잘려나가기 때문이다. 그래서 본체는 마지막에 한 번만 그린다.
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

        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas

            if (!pressed) {
                // 자기 색 번짐. 버튼 모양을 그대로 따라가며 넓게 퍼진다.
                val bloom = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = fill.toArgb()
                    setShadowLayer(blur * 2.2f, 0f, off * 0.6f, fill.copy(alpha = 0.55f).toArgb())
                }
                fw.drawRoundRect(rect, cr, cr, bloom)

                // 표면 그림자. 컬러 요소도 같은 광원 아래 있어야 화면이 어긋나지 않는다.
                val dark = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = fill.toArgb()
                    setShadowLayer(blur, off, off, p.shadowColor.copy(alpha = 0.7f).toArgb())
                }
                fw.drawRoundRect(rect, cr, cr, dark)

                val light = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = fill.toArgb()
                    setShadowLayer(blur, -off, -off, p.light.copy(alpha = 0.6f).toArgb())
                }
                fw.drawRoundRect(rect, cr, cr, light)
            } else {
                // 눌리면 번짐이 줄고 표면에 가라앉는다.
                val sunk = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = fill.toArgb()
                    setShadowLayer(blur * 0.4f, off * 0.3f, off * 0.3f, p.shadowColor.toArgb())
                }
                fw.drawRoundRect(rect, cr, cr, sunk)
            }
        }

        // 본체는 선명해야 한다. 번짐 위에 한 번만 칠한다.
        drawRoundRect(
            if (pressed) fill.shade(0.92f) else fill,
            cornerRadius = CornerRadius(cr, cr),
        )
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
