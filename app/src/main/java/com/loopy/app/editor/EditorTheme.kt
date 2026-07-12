package com.loopy.app.editor

import androidx.compose.ui.graphics.Color

/**
 * 편집기 공용 색과 상수.
 *
 * 트레이서와 블록이 같은 색 규칙을 따라야 "이 블록이 저 궤적"이라는 연결이 눈에 보인다.
 * 촬영된 스트로크는 파랑, 편집기에서 추가한 것은 초록으로 구분한다.
 */
internal val TraceStart = Color(0xFF3B82F6)
internal val TraceEnd = Color(0xFFEFF5FF)
internal val AddedGreen = Color(0xFF20C997)
internal val AddedEnd = Color(0xFFF2FFFB)

/** 재생헤드 앞뒤로 궤적을 보여줄 범위. 너무 넓으면 화면이 지저분해진다. */
internal const val WINDOW_MS = 150L

/** 타임라인 기본 배율. 핀치로 바뀐다. */
internal const val DP_PER_SEC = 68f

/** 분:초.백분초. 프레임 단위로 맞추려면 소수 둘째 자리가 필요하다. */
internal fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%02d".format(s / 60, s % 60, (ms % 1000) / 10)
}

/** 입력 필드용 초 단위 평문. */
internal fun fmtPlain(ms: Long): String = "%.2f".format(ms / 1000.0)

/** "6.04" 또는 "1:06.04" → ms. 둘 다 받는 이유는 사람마다 쓰는 방식이 달라서다. */
internal fun parseTime(s: String, total: Long): Long? {
    val str = s.trim()
    if (str.isEmpty()) return null
    return runCatching {
        if (str.contains(":")) {
            val parts = str.split(":")
            val m = parts[0].trim().toLong()
            val sec = parts[1].trim().toDouble()
            m * 60_000L + (sec * 1000).toLong()
        } else {
            (str.toDouble() * 1000).toLong()
        }
    }.getOrNull()?.coerceIn(0L, total)
}
