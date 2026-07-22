package com.loopy.app.overlay

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 오버레이 UI 조각들 — 상태 없는 순수 뷰 헬퍼.
 *
 * OverlayService 가 수명주기·녹화·재생·오버레이 조립을 한꺼번에 하느라 763줄이었다. 그중 "생김새"만
 * 아는 잎 함수들을 여기로 뺀다. Context 확장이라 서비스 안에서는 호출부가 그대로다.
 */

/** dp → px. */
fun Context.dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
).toInt()

/** 원형 단색 배경. */
fun circleBg(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** 둥근 모서리 배경(알약). */
fun pill(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius.toFloat()
}

/** 컨트롤 바의 원형 아이콘 버튼. */
fun Context.iconBtn(iconRes: Int, tint: Int, bgTint: Int, onClick: () -> Unit): ImageButton =
    ImageButton(this).apply {
        setImageResource(iconRes)
        setColorFilter(tint)
        background = circleBg(bgTint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val sz = dp(42)
        layoutParams = LinearLayout.LayoutParams(sz, sz)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        setOnClickListener { onClick() }
    }

/** 작은 안내 문구. */
fun Context.hint(t: String): TextView = TextView(this).apply {
    text = t
    setTextColor(0xFF9AA0B4.toInt())
    textSize = 10f
    setPadding(dp(2), dp(8), 0, dp(4))
    letterSpacing = 0.06f
}

/** 목록 한 줄: 색 점 + 이름. */
fun Context.listRow(t: String, color: Int, onClick: () -> Unit): LinearLayout {
    val ctx = this
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(9), dp(10), dp(9))
        background = pill(0x0A000000, dp(12)) // 아주 옅은 행 배경
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
        addView(TextView(ctx).apply {
            text = t
            setTextColor(0xFF2B2D42.toInt())
            textSize = 13f
            setPadding(dp(10), 0, 0, 0)
        })
        setOnClickListener { onClick() }
    }
}
