package com.loopy.app.macro

/**
 * 저장되는 매크로. strokes = 원본 좌표 타임라인들(탭/홀드/스와이프/조이스틱 통합).
 * videoPath = (선택) 녹화 시 함께 저장한 화면 영상 mp4 경로. 편집기에서 참조.
 */
data class Macro(
    val id: String,
    val name: String,
    val createdAt: Long,
    val strokes: List<Stroke>,
    val videoPath: String? = null,
)
