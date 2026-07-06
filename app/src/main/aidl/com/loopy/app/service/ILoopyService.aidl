// Shizuku UserService 인터페이스. elevated(shell) 프로세스에서 실행되는 메서드들.
package com.loopy.app.service;

interface ILoopyService {
    // Shizuku 서버가 서비스를 종료할 때 호출하는 예약 트랜잭션 ID (고정).
    void destroy() = 16777114;
    void exit() = 1;

    // 좌표를 durationMs 동안 누르고 뗌. 탭(짧게)/홀드(길게) 통합.
    void press(int x, int y, int durationMs) = 2;

    // (x1,y1) → (x2,y2) 로 durationMs 동안 스와이프.
    void swipe(int x1, int y1, int x2, int y2, int durationMs) = 3;
}
