package com.loopy.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 오프화이트 배경 위에 파스텔(피치/라벤더/민트) 블롭을 크고 흐리게 겹쳐 아주 느리게
 * 움직여 mesh 느낌을 낸다. 밝은 배경이라 alpha 를 살짝 높여 은은하게 보이게 한다.
 * 재생 중에는 animate=false 로 꺼서 GPU/배터리를 아낀다.
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "mesh")
    val phase = if (animate) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(30_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        ).value
    } else 0f

    Canvas(modifier = modifier.fillMaxSize().background(LoopyBg)) {
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.85f

        blob(MeshPeach, w * (0.22f + 0.10f * cos(phase)), h * (0.18f + 0.08f * sin(phase)), r * 0.9f)
        blob(MeshMint, w * (0.82f + 0.10f * sin(phase * 0.9f)), h * (0.26f + 0.10f * cos(phase * 1.1f)), r * 0.85f)
        blob(MeshLavender, w * (0.55f + 0.12f * cos(phase * 1.3f)), h * (0.88f + 0.06f * sin(phase)), r)
        blob(MeshPeach, w * (0.12f + 0.08f * sin(phase * 0.7f)), h * (0.82f + 0.08f * cos(phase * 0.8f)), r * 0.6f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.blob(
    color: Color, cx: Float, cy: Float, radius: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.65f), color.copy(alpha = 0f)),
            center = Offset(cx, cy),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, cy),
    )
}
