package com.loopy.app.blocks

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.loopy.app.ui.theme.palette

/**
 * 블록 모양.
 *
 * 모양이 문법이다. 모자 블록은 위가 둥글어 무엇도 위에 붙일 수 없고, 마개 블록은 아래가
 * 평평해 뒤에 이어붙일 수 없다. 규칙을 설명하는 대신 손으로 만져 알게 한다.
 *
 * 블록은 배경과 다른 색이므로 뉴모피즘의 "같은 재질" 규칙 밖에 있다. 회색 그림자만 씌우면
 * 색이 탁해지고 스티커처럼 얹힌 것 같다. 그래서 자기 색을 물감이 번지듯 퍼뜨린다. 번짐은
 * 블록 모양을 그대로 따라가므로 모서리에서 어긋나지 않는다.
 *
 * 그리는 순서가 중요하다. 번짐을 먼저 깔고 본체를 마지막에 한 번만 칠한다. 번짐 위에 본체를
 * 덧칠하지 않으면 경계 안쪽이 잘려 이중 테가 생긴다.
 */
fun Modifier.blockShape(
    shape: BlockShape,
    color: Color,
    innerTop: Float = 0f,
    innerHeight: Float = 0f,
    lifted: Boolean = false,
): Modifier = composed {
    val p = palette
    drawBehind {
        val path = blockPath(shape, size.width, size.height, innerTop, innerHeight)
        val off = (if (lifted) 10.dp else 5.dp).toPx()
        val blur = (if (lifted) 24.dp else 13.dp).toPx()

        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val native = path.asAndroidPath()

            val bloom = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = color.toArgb()
                setShadowLayer(blur * 1.9f, 0f, off * 0.5f, color.copy(alpha = 0.6f).toArgb())
            }
            fw.drawPath(native, bloom)

            val dark = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = color.toArgb()
                setShadowLayer(blur, off, off, p.shadowColor.copy(alpha = 0.65f).toArgb())
            }
            fw.drawPath(native, dark)

            val light = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = color.toArgb()
                setShadowLayer(blur * 0.8f, -off * 0.7f, -off * 0.7f, p.light.copy(alpha = 0.5f).toArgb())
            }
            fw.drawPath(native, light)
        }

        drawPath(path, color)
    }
}

/**
 * 블록 외곽선.
 *
 * @param innerTop C블록에서 자식이 들어가는 홈의 시작 y
 * @param innerHeight 그 홈의 높이
 */
private fun DrawScope.blockPath(
    shape: BlockShape,
    w: Float,
    h: Float,
    innerTop: Float,
    innerHeight: Float,
): Path {
    val cr = 8.dp.toPx()
    val nw = 26.dp.toPx()
    val nd = 5.dp.toPx()
    val nl = 18.dp.toPx()
    val wall = 14.dp.toPx()

    return Path().apply {
        when (shape) {
            BlockShape.HAT -> {
                val cap = 14.dp.toPx()
                moveTo(0f, cap)
                cubicTo(0f, cap * 0.1f, w * 0.3f, -cap * 0.5f, w * 0.5f, -cap * 0.2f)
                cubicTo(w * 0.7f, cap * 0.1f, w, cap * 0.1f, w, cap)
                lineTo(w, h - nd)
                bottomEdge(w, h, nl, nw, nd, cr)
                lineTo(0f, cap)
                close()
            }

            BlockShape.CAP -> {
                topEdge(w, nl, nw, nd, cr)
                lineTo(w, h - cr)
                quadraticBezierTo(w, h, w - cr, h)
                lineTo(cr, h)
                quadraticBezierTo(0f, h, 0f, h - cr)
                lineTo(0f, nd + cr)
                quadraticBezierTo(0f, nd, cr, nd)
                close()
            }

            BlockShape.STACK, BlockShape.FORK -> {
                topEdge(w, nl, nw, nd, cr)
                lineTo(w, h - nd)
                bottomEdge(w, h, nl, nw, nd, cr)
                close()
            }

            BlockShape.C_BLOCK -> {
                val top = innerTop
                val bot = innerTop + innerHeight

                topEdge(w, nl, nw, nd, cr)
                lineTo(w, top - nd)
                // 홈 입구
                lineTo(wall + nl + nw, top - nd)
                lineTo(wall + nl + nw * 0.7f, top)
                lineTo(wall + nl + nw * 0.3f, top)
                lineTo(wall + nl, top - nd)
                lineTo(wall, top - nd)
                // 왼쪽 벽
                lineTo(wall, bot)
                // 발 윗면
                lineTo(wall + nl, bot)
                lineTo(wall + nl + nw * 0.3f, bot + nd)
                lineTo(wall + nl + nw * 0.7f, bot + nd)
                lineTo(wall + nl + nw, bot)
                lineTo(w - cr, bot)
                quadraticBezierTo(w, bot, w, bot + cr)
                lineTo(w, h - nd)
                bottomEdge(w, h, nl, nw, nd, cr)
                close()
            }
        }
    }
}

/** 위쪽 가장자리. 오목한 홈이 앞 블록의 볼록을 받는다. */
private fun Path.topEdge(w: Float, nl: Float, nw: Float, nd: Float, cr: Float) {
    moveTo(cr, nd)
    lineTo(nl, nd)
    lineTo(nl + nw * 0.3f, nd * 2f)
    lineTo(nl + nw * 0.7f, nd * 2f)
    lineTo(nl + nw, nd)
    lineTo(w - cr, nd)
    quadraticBezierTo(w, nd, w, nd + cr)
}

/** 아래쪽 가장자리. 볼록이 다음 블록의 오목에 물린다. */
private fun Path.bottomEdge(w: Float, h: Float, nl: Float, nw: Float, nd: Float, cr: Float) {
    quadraticBezierTo(w, h - nd, w - cr, h - nd)
    lineTo(nl + nw, h - nd)
    lineTo(nl + nw * 0.7f, h)
    lineTo(nl + nw * 0.3f, h)
    lineTo(nl, h - nd)
    lineTo(cr, h - nd)
    quadraticBezierTo(0f, h - nd, 0f, h - nd - cr)
    lineTo(0f, nd + cr)
    quadraticBezierTo(0f, nd, cr, nd)
}
