package com.loopy.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.loopy.app.R

/**
 * 오버레이 FAB. 기본은 그라데이션 원 + 흰 루프(loop) 글리프.
 * Deep SLock 시엔 setMoon(true)로 노란 달 아이콘으로 교체된다.
 */
class FabLogoView(context: Context) : View(context) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()
    private var moonMode = false
    private val moon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_ov_moon)
        ?.mutate()?.apply { setTint(0xFFFFD166.toInt()) } // 노란 달

    fun setMoon(on: Boolean) {
        if (moonMode == on) return
        moonMode = on
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        circlePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            0xFF6C7BFF.toInt(), 0xFF5FD0E8.toInt(), Shader.TileMode.CLAMP,
        )
        loopPaint.strokeWidth = w * 0.07f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, w / 2f, circlePaint)

        if (moonMode && moon != null) {
            val inset = (w * 0.26f).toInt()
            moon.setBounds(inset, inset, width - inset, height - inset)
            moon.draw(canvas)
            return
        }

        val r = w * 0.24f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, -35f, 295f, false, loopPaint)
        arrow.reset()
        arrow.moveTo(cx + r * 0.5f, cy - r * 1.15f)
        arrow.lineTo(cx + r * 1.15f, cy - r * 0.55f)
        arrow.lineTo(cx + r * 0.35f, cy - r * 0.35f)
        canvas.drawPath(arrow, loopPaint)
    }
}
