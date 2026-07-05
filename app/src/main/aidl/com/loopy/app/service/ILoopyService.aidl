// Shizuku UserService 인터페이스. elevated(shell) 프로세스에서 실행되는 메서드들.
package com.loopy.app.service;

interface ILoopyService {
    // Shizuku 서버가 서비스를 종료할 때 호출하는 예약 트랜잭션 ID (고정).
    void destroy() = 16777114;
    void exit() = 1;

    // 화면 픽셀 좌표에 탭 주입.
    void tap(int x, int y) = 2;

    // (x1,y1) → (x2,y2) 로 durationMs 동안 스와이프.
    void swipe(int x1, int y1, int x2, int y2, int durationMs) = 3;

    // 같은 자리 더블탭. gapMs = 두 탭 사이 간격.
    void doubleTap(int x, int y, int gapMs) = 4;

    // 같은 자리에서 durationMs 동안 누르고 있기(홀드/롱프레스).
    void hold(int x, int y, int durationMs) = 5;
}
