package com.loopy.app.macro

/** 한 시점의 터치 좌표. t = 스트로크 시작 기준 ms, nx/ny = panel 정규화(0~1). */
data class TouchSample(val t: Long, val nx: Float, val ny: Float)

/**
 * 스트로크 = 손가락 하나가 내려와서(첫 샘플) 떼질 때까지의 좌표 타임라인.
 * 탭/홀드/스와이프/조이스틱이 전부 이 하나로 표현된다.
 *
 * startMs = 매크로 전체 기준 절대 시작 시각(ms). 멀티터치 동시 재생의 핵심 —
 *           여러 스트로크의 startMs 가 겹치면 그만큼 동시에 재생된다.
 * durationMs = down→up 총 지속시간(홀드 유지시간 재현용).
 */
data class Stroke(
    val startMs: Long,
    val durationMs: Long,
    val samples: List<TouchSample>,
    val added: Boolean = false,
    /** 이 스트로크가 캡처될 때의 화면 회전(0/90/180/270). -1이면 매크로 기본 rotation 사용. */
    val rotation: Int = -1,
)
