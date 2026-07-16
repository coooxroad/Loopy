package com.loopy.app.blocks

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * 렌더는 스크래치를 따른다: 불투명 평면 + 얇은 진한 테두리 + 아주 옅은 드롭 그림자 하나.
 * 예전엔 자기 색을 크게 번지게(bloom) 그렸는데, 블록을 딱 물려 놓으면 번짐끼리 겹쳐
 * 반투명하게 뭉개졌다. 스크래치 블록은 서로 비치지 않는 불투명 조각이라 또렷이 맞물린다.
 */
fun Modifier.blockShape(
    shape: BlockShape,
    color: Color,
    innerTop: Float = 0f,
    innerHeight: Float = 0f,
    lifted: Boolean = false,
): Modifier = drawBehind {
    // 스크래치식: 불투명 평면 + 얇은 진한 테두리 + 아주 옅은 드롭 그림자 하나.
    // 예전엔 자기 색을 크게 번지게(bloom) 그려서, 블록을 물려 놓으면 번짐끼리 겹쳐
    // 반투명하게 뭉개졌다. 스크래치 블록은 서로 비치지 않는 불투명 조각이라 또렷이 맞물린다.
    val path = blockPath(shape, size.width, size.height, innerTop, innerHeight)

    val elev = (if (lifted) 6.dp else 2.dp).toPx()
    val sblur = (if (lifted) 12.dp else 5.dp).toPx()
    drawIntoCanvas { canvas ->
        val body = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = color.toArgb()
            setShadowLayer(sblur, 0f, elev, android.graphics.Color.argb(if (lifted) 90 else 56, 0, 0, 0))
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), body)
    }

    // 얇은 진한 테두리 — 블록색을 어둡게. 또렷함은 여기서 나온다.
    val edge = Color(color.red * 0.70f, color.green * 0.70f, color.blue * 0.70f, 1f)
    drawPath(path, edge, style = Stroke(width = 1.5.dp.toPx()))
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
    // 그려지는 노치 깊이와 스냅이 계산하는 겹침이 같은 값을 봐야 블록이 맞물린다.
    val nd = NOTCH_DEPTH.dp.toPx()
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

            BlockShape.STACK -> {
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
