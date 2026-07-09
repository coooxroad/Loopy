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
)
