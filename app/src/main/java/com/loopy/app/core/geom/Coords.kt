package com.loopy.app.core.geom

import androidx.compose.ui.geometry.Offset

/**
 * 좌표 변환의 단일 진실 공급원.
 *
 * 터치 샘플의 nx/ny 는 **터치 패널 기준 정규화 좌표**다. 기기를 돌려도 값이 변하지 않는다.
 * 반대로 화면·영상은 회전에 따라 배치가 달라지므로, 그릴 때마다 그 시점의 회전으로 변환해야 한다.
 * 변환 로직이 여러 곳에 흩어지면 회전 규칙을 바꿀 때마다 전부 고쳐야 하므로 여기 하나로 모은다.
 */
object Coords {

    /** 패널 좌표 → 회전된 화면 좌표(0~1). 재생 측 좌표 변환과 반드시 같은 규칙이어야 한다. */
    fun rotate(nx: Float, ny: Float, rotDeg: Int): Pair<Float, Float> = when (rotDeg) {
        90 -> ny to (1f - nx)
        180 -> (1f - nx) to (1f - ny)
        270 -> (1f - ny) to nx
        else -> nx to ny
    }

    /** 화면 델타 → 패널 델타. rotate 의 역변환. */
    fun unrotateDelta(dx: Float, dy: Float, rotDeg: Int): Pair<Float, Float> = when (rotDeg) {
        90 -> (-dy) to dx
        180 -> (-dx) to (-dy)
        270 -> dy to (-dx)
        else -> dx to dy
    }

    fun isLandscape(rotDeg: Int) = rotDeg == 90 || rotDeg == 270

    /** 뷰 안에 종횡비 aspect 인 내용을 letterbox 로 맞춘 사각형. */
    fun fitRect(viewW: Float, viewH: Float, aspect: Float): Rect {
        val viewAspect = viewW / viewH
        return if (aspect > viewAspect) {
            val h = viewW / aspect
            Rect(0f, (viewH - h) / 2f, viewW, h)
        } else {
            val w = viewH * aspect
            Rect((viewW - w) / 2f, 0f, w, viewH)
        }
    }

    /**
     * 스트로크를 그릴 실제 영역.
     *
     * 화면 녹화 영상은 프레임 해상도가 고정(보통 세로)이라, 녹화 중 기기를 돌리면 가로 화면이
     * 그 세로 프레임 **안에** 축소되어 배치된다. 따라서 회전 구간의 스트로크는 프레임 전체가
     * 아니라 그 축소된 영역에 매핑해야 한다.
     *
     * @param videoRect 영상 프레임이 뷰에 그려진 사각형
     * @param screenAspect 기기 세로 화면의 종횡비(w/h)
     */
    fun contentRect(videoRect: Rect, rotDeg: Int, screenAspect: Float): Rect {
        if (!isLandscape(rotDeg)) return videoRect
        val landAspect = if (screenAspect > 0f) 1f / screenAspect else 16f / 9f
        val inner = fitRect(videoRect.w, videoRect.h, landAspect)
        return Rect(videoRect.x + inner.x, videoRect.y + inner.y, inner.w, inner.h)
    }

    /** 패널 좌표 → 뷰 픽셀. 회전·레터박스·회전구간 축소를 모두 반영한다. */
    fun toView(nx: Float, ny: Float, rotDeg: Int, videoRect: Rect, screenAspect: Float): Offset {
        val (rx, ry) = rotate(nx, ny, rotDeg)
        val area = contentRect(videoRect, rotDeg, screenAspect)
        return Offset(area.x + rx * area.w, area.y + ry * area.h)
    }

    /** 뷰 픽셀 델타 → 패널 정규화 델타. 드래그로 스트로크를 옮길 때 사용. */
    fun deltaToPanel(
        dx: Float, dy: Float, rotDeg: Int, videoRect: Rect, screenAspect: Float,
    ): Pair<Float, Float> {
        val area = contentRect(videoRect, rotDeg, screenAspect)
        return unrotateDelta(dx / area.w, dy / area.h, rotDeg)
    }
}

data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
}
