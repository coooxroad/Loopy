package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 캡처한 raw 이벤트를 sendevent 로 되쏜다.
 *
 * 성능/타이밍의 핵심: 이벤트마다 sendevent 를 "따로" 실행하면(프로세스 포크) 느리고
 * 타이밍이 뭉개진다. 그래서 전체 시퀀스를 sleep 이 섞인 "하나의 셸 스크립트"로 만들어
 * 한 프로세스에서 실행한다. (M1b-1 은 짧은 시퀀스라 이걸로 충분. 긴 매크로의
 * 추가 최적화는 나중에.)
 *
 * value 변환: getevent 는 0xffffffff 를 그대로 준다(=트래킹ID 해제, 의미상 -1).
 * sendevent 에는 부호 있는 정수로 넣어야 하므로 32비트 부호 해석(Long→Int)한다.
 * 예: 0xffffffff → -1, 0x7a1 → 1953.
 */
class Player(
    private val scope: CoroutineScope,
) {

    fun play(dev: TouchDevice, events: List<RawEvent>, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            if (events.isNotEmpty()) {
                val sb = StringBuilder()
                var prev = events.first().tMicros
                for (e in events) {
                    val dt = e.tMicros - prev
                    if (dt >= 5_000) { // 5ms 이상 간격만 sleep 으로 재현
                        sb.append("sleep ")
                            .append(String.format(Locale.US, "%.3f", dt / 1_000_000.0))
                            .append('\n')
                    }
                    sb.append("sendevent ").append(dev.path).append(' ')
                        .append(e.type).append(' ')
                        .append(e.code).append(' ')
                        .append(e.value.toInt()) // 32비트 부호 해석
                        .append('\n')
                    prev = e.tMicros
                }
                Shell.exec(sb.toString())
            }
            onDone()
        }
    }
}
