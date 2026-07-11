package com.loopy.app.macro

/**
 * 저장되는 매크로. strokes = 원본 좌표 타임라인들(탭/홀드/스와이프/조이스틱 통합).
 * videoPath = (선택) 함께 녹화한 화면 영상 mp4 경로.
 * videoOffsetMs = 매크로 첫 이벤트가 영상 시작 후 몇 ms 뒤인지(싱크 보정용).
 *                 편집기에서 스트로크 startMs → 영상시간 = videoOffsetMs + startMs.
 */
data class Macro(
    val id: String,
    val name: String,
    val createdAt: Long,
    val strokes: List<Stroke>,
    val videoPath: String? = null,
    val videoOffsetMs: Long = 0L,
    val rotation: Int = 0, // 녹화 시작 시 화면 회전(0/90/180/270°)
    /**
     * 녹화 중 회전 변화 타임라인. (매크로 기준 ms, 회전값).
     * 첫 항목은 보통 (0, rotation). 재생/편집 시 해당 시각의 회전을 찾아 좌표를 매핑한다.
     */
    val rotationEvents: List<RotationEvent> = emptyList(),
)

/** 회전 변화 지점. tMs = 매크로 시작 기준 ms, rotation = 0/90/180/270. */
data class RotationEvent(val tMs: Long, val rotation: Int)
