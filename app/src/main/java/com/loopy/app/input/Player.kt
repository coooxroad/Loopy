package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 캡처한 raw 이벤트를 sendevent 로 되쏜다.
 *
 * 전체 시퀀스를 sleep 이 섞인 "하나의 셸 스크립트"로 만들어 한 프로세스에서 실행한다.
 * 진단 모드: execDiag 로 sendevent 의 exit/stderr 를 받아 onResult 로 돌려준다.
 *
 * value 변환: getevent 는 0xffffffff(=트래킹ID 해제)를 그대로 준다.
 * sendevent 엔 부호 있는 32비트로 넣어야 하므로 Long→Int 로 부호 해석한다.
 * 예: 0xffffffff → -1, 0x7a1 → 1953.
 */
class Player(
    private val scope: CoroutineScope,
) {

    fun play(dev: TouchDevice, events: List<RawEvent>, onResult: (String) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            if (events.isEmpty()) {
                withContext(Dispatchers.Main) { onResult("이벤트 없음") }
                return@launch
            }
            val sb = StringBuilder()
            var prev = events.first().tMicros
            for (e in events) {
                val dt = e.tMicros - prev
                if (dt >= 5_000) {
                    sb.append("sleep ")
                        .append(String.format(Locale.US, "%.3f", dt / 1_000_000.0))
                        .append('\n')
                }
                sb.append("sendevent ").append(dev.path).append(' ')
                    .append(e.type).append(' ')
                    .append(e.code).append(' ')
                    .append(e.value.toInt())
                    .append('\n')
                prev = e.tMicros
            }
            val diag = Shell.execDiag(sb.toString())
            withContext(Dispatchers.Main) { onResult(diag) }
        }
    }
}
