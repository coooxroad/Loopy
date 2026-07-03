package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * M1a 재생 엔진의 최소 버전. `input` 명령으로 터치를 주입한다.
 *
 * 중요: Shell.exec 안의 waitFor() 는 프로세스가 끝날 때까지 스레드를 붙잡는다.
 * 이걸 메인 스레드에서 부르면 UI 가 얼어 "응답하지 않음"으로 앱이 죽는다.
 * 그래서 모든 주입은 IO 디스패처에서 돌린다.
 *
 * 참고: `input tap/swipe` 는 내부적으로 InputManager.injectInputEvent 를 쓴다.
 * 여기서 탭이 먹히면, M1b 에서 injectInputEvent 를 직접 부르는 정밀 재생도
 * 같은 경로라 동작한다는 신호다. 다만 `input` 은 매 호출이 프로세스를 포크해서
 * 느리고 정밀 타이밍/멀티터치가 안 되므로, 실제 매크로 재생(M1b)에서는
 * UserService + injectInputEvent 로 교체할 예정. 지금은 "주입이 되는가"만 확인.
 */
class InputInjector(
    private val scope: CoroutineScope,
) {

    /** 화면 픽셀 좌표 (x, y) 를 한 번 탭. 백그라운드(IO)에서 실행. */
    fun tap(x: Int, y: Int, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            Shell.exec("input tap $x $y")
            onDone()
        }
    }

    /** (x1,y1) → (x2,y2) 로 durationMs 동안 스와이프. 백그라운드(IO)에서 실행. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            Shell.exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
            onDone()
        }
    }
}
