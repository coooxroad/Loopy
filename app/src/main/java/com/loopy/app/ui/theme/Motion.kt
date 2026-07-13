package com.loopy.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 모션.
 *
 * 값이 화면마다 다르면 앱이 여러 사람이 만든 것처럼 느껴진다. 시간과 곡선을 여기서만 정한다.
 *
 * 원칙: 사용자가 일으킨 변화는 빠르게(누름, 토글), 시스템이 보여주는 변화는 여유 있게(시트, 전환).
 * 반대로 하면 앱이 굼뜨거나 정신없어 보인다.
 */
object Motion {

    /** 즉각적인 반응. 누름처럼 손가락과 붙어 있어야 하는 것. */
    const val INSTANT = 100

    /** 토글, 페이드. */
    const val FAST = 200

    /** 화면 전환. */
    const val NORMAL = 300

    /** 바텀시트처럼 큰 것이 움직일 때. */
    const val SLOW = 450

    /** 기본 감속. 대부분의 전환. */
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 강조. 시트나 큰 전환에서 끝을 확실히 맺는다. */
    val emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> quick(): FiniteAnimationSpec<T> = tween(INSTANT, easing = standard)

    fun <T> snappy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> smooth(): FiniteAnimationSpec<T> = tween(FAST, easing = standard)

    fun <T> screen(): FiniteAnimationSpec<T> = tween(NORMAL, easing = emphasized)

    fun <T> sheet(): FiniteAnimationSpec<T> = tween(SLOW, easing = emphasized)
}
