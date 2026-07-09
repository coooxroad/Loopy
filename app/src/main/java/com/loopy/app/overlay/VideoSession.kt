package com.loopy.app.overlay

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 화면 녹화 "세션"(MediaProjection 권한) 상태를 앱 UI ↔ 오버레이 서비스가 공유.
 * 세션을 한 번 켜두면(권한 1회) 오버레이 녹화 시 팝업/앱전환 없이 재사용된다.
 */
object VideoSession {
    /** 세션이 살아있나 (UI 스위치 관찰용). */
    var active by mutableStateOf(false)

    /** 권한 결과(앱에서 받아 서비스로 전달). */
    var code: Int = 0
    var data: Intent? = null
}
