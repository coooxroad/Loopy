package com.loopy.app.macro

/**
 * 매크로들을 엮은 플레이리스트.
 *  - macroIds: 패턴 순서(중복 가능). 예: [A, A, B, C]
 *  - shuffle: 켜면 매 사이클마다 그 패턴을 섞어서(셔플백) 재생
 *  - cycles: 반복 횟수. 0 = 무한
 */
data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val macroIds: List<String>,
    val shuffle: Boolean,
    val cycles: Int,
)
