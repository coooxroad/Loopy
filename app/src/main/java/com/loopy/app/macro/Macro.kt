package com.loopy.app.macro

import com.loopy.app.input.GestureRecorder

/** 저장되는 매크로 하나. actions 는 녹화로 만들어진 재생 단위 리스트. */
data class Macro(
    val id: String,
    val name: String,
    val createdAt: Long,
    val actions: List<GestureRecorder.Action>,
)
