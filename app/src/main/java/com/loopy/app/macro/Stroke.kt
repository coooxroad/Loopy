package com.loopy.app.macro

/** 한 시점의 터치 좌표. t = 스트로크 시작 기준 ms, nx/ny = panel 정규화(0~1). */
data class TouchSample(val t: Long, val nx: Float, val ny: Float)

/**
 * 스트로크 = 손가락이 내려와서(첫 샘플) 떼질 때까지의 좌표 타임라인.
 * 탭/홀드/스와이프/조이스틱이 전부 이 하나로 표현된다(움직임·시간의 차이일 뿐).
 * delayMs = 이전 스트로크가 끝난 뒤 이 스트로크 시작까지의 대기.
 */
data class Stroke(val delayMs: Long, val samples: List<TouchSample>)
