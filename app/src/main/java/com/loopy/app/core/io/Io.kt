package com.loopy.app.core.io

import com.loopy.app.core.record.Stroke

/**
 * 시스템에 실제로 손을 대는 모든 경로의 단일 통로.
 *
 * Shizuku(ADB 권한)가 있으면 접근성 서비스로는 불가능한 일들이 가능해진다.
 * 터치 주입, 설정 변경, 화면 캡처, 앱 제어가 전부 여기를 거치므로,
 * 이후 추가될 Material(이미지 인식, 스크립트, API 연동)도 같은 통로만 알면 된다.
 *
 * 구현을 인터페이스 뒤에 두는 이유:
 *  - 테스트 시 가짜 구현으로 대체 가능
 *  - Shizuku 외 백엔드(루트, 접근성)를 나중에 붙일 여지
 */
interface Io {

    val available: Boolean

    // ── 입력 ──

    /**
     * 궤적 하나를 재생하고, 끝날 때까지 기다린다.
     *
     * 블로킹이어야 하는 이유: 트리의 순서가 곧 타이밍이기 때문이다. 궤적이 도는 동안
     * 다음 블록이 시작해버리면 대기 블록의 값이 의미를 잃는다.
     */
    suspend fun playStroke(stroke: Stroke)

    /** 진행 중인 주입을 중단. 킬 스위치가 어디서든 동작하려면 필요하다. */
    fun stopPlayback()

    /** 터치 캡처 시작. 콜백은 패널 정규화 좌표(회전 불변)를 넘긴다. */
    fun startCapture(onPoint: (slot: Int, nx: Float, ny: Float, down: Boolean) -> Unit)

    fun stopCapture()

    // ── 화면 ──

    /** 현재 화면 회전(0/90/180/270). */
    fun rotation(): Int

    /** 화면 픽셀 크기. */
    fun screenSize(): Pair<Int, Int>

    /**
     * 현재 화면 캡처. 이미지 인식 Material 이 여기에 의존한다.
     * 아직 구현하지 않았지만, 자리를 비워둬야 나중에 호출부가 흔들리지 않는다.
     */
    suspend fun captureScreen(): ScreenShot?

    // ── 시스템 ──

    /** `settings put` 계열. 밝기·볼륨 등 Material 이 사용한다. */
    suspend fun putSetting(namespace: String, key: String, value: String): Boolean

    suspend fun getSetting(namespace: String, key: String): String?

    /** 임의 셸 명령. 마지막 수단이자, 아직 전용 API 가 없는 기능의 임시 통로. */
    suspend fun shell(cmd: String): String?

    /**
     * 화면 위 검은 레이어.
     *
     * 터치는 통과시켜야 한다. 화면을 가린 채로도 매크로가 계속 돌아야 하기 때문이다.
     * 오버레이 서비스만이 윈도우를 띄울 수 있으므로, 구현은 그쪽에 연결된다.
     */
    fun setDim(on: Boolean)

    fun isDimmed(): Boolean

    /** 울리는 알람 정지. */
    suspend fun dismissAlarm(): Boolean

    /** 앱 실행 / 종료. 트리거·액션 양쪽에서 쓰인다. */
    suspend fun launchApp(pkg: String): Boolean

    suspend fun forceStop(pkg: String): Boolean

    /** 현재 최상위 앱 패키지. 트리거(앱 실행 감지)와 조건에서 쓰인다. */
    suspend fun foregroundApp(): String?
}

/** 캡처된 화면. 이미지 인식이 이 위에서 템플릿 매칭을 수행한다. */
class ScreenShot(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val rotation: Int,
)
