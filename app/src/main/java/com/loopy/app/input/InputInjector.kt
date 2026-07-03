package com.loopy.app.input

import com.loopy.app.shizuku.Shell

/**
 * M1a 재생 엔진의 최소 버전. `input` 명령으로 터치를 주입한다.
 *
 * 참고: `input tap/swipe` 는 내부적으로 InputManager.injectInputEvent 를 쓴다.
 * 즉 여기서 탭이 먹히면, M1b 에서 injectInputEvent 를 직접 부르는 정밀 재생도
 * 같은 경로라 동작한다는 신호다. 다만 `input` 은 매 호출이 프로세스를 포크해서
 * 느리고 정밀 타이밍/멀티터치가 안 되므로, 실제 매크로 재생(M1b)에서는
 * UserService + injectInputEvent 로 교체할 예정. 지금은 "주입이 되는가"만 확인.
 */
class InputInjector {

    /** 화면 픽셀 좌표 (x, y) 를 한 번 탭. */
    fun tap(x: Int, y: Int) {
        Shell.exec("input tap $x $y")
    }

    /** (x1,y1) → (x2,y2) 로 durationMs 동안 스와이프. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        Shell.exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }
}
