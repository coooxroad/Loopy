package com.loopy.app.core.record

/**
 * 한 시점의 터치 좌표.
 *
 * t = 스트로크 시작 기준 ms. nx/ny = 터치 패널 정규화(0~1).
 * 패널 좌표는 기기를 돌려도 변하지 않는다. 회전은 재생·표시 시점에 적용한다.
 */
data class TouchSample(val t: Long, val nx: Float, val ny: Float)

/**
 * 손가락 하나가 내려와서 떨어질 때까지의 궤적.
 *
 * 시작 시각을 갖지 않는다. 언제 재생될지는 이 스트로크를 참조하는 Material 트리가 정한다.
 * 궤적은 "무엇을 그렸는가"만 알고, "언제"는 트리의 순서와 대기 블록이 표현한다.
 * 그래서 같은 궤적을 여러 곳에서 다른 타이밍으로 재사용할 수 있다.
 *
 * rotation = 이 궤적이 캡처될 때의 화면 회전(0/90/180/270). -1이면 녹화 기본값을 따른다.
 */
data class Stroke(
    val durationMs: Long,
    val samples: List<TouchSample>,
    val rotation: Int = -1,
)
