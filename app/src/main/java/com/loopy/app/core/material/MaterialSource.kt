package com.loopy.app.core.material

/**
 * 저장된 Material 을 id 로 찾아 주는 능력.
 *
 * "빌드 실행"처럼 다른 빌드를 불러와 돌리는 블록이 대상을 이걸로 얻는다. 코어(엔진)는 저장
 * 방식(파일·DB·네트워크)을 몰라야 하므로, [com.loopy.app.core.io.Io] 와 같은 결로 port 만
 * 둔다 — 실제 구현(파일 저장소)은 바깥에서 주입한다. 그래야 도메인이 안드로이드를 모르고,
 * 테스트에서 가짜 소스로 갈아끼울 수 있다.
 *
 * 확장점: 앞으로 API·커스텀 Material 도 "다른 Material 을 id 로 참조"할 때 이걸 재사용한다.
 */
fun interface MaterialSource {
    /** 없으면 null. 지워진 참조여도 실행이 무너지지 않게, 부르는 쪽이 이를 감안한다. */
    fun find(id: String): Material?

    companion object {
        /** 아무것도 못 찾는 기본값. 실행 맥락에 저장소가 주입되지 않았을 때의 안전한 바닥. */
        val Empty = MaterialSource { null }
    }
}
