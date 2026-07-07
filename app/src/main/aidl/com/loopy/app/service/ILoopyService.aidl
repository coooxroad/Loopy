// Shizuku UserService 인터페이스. elevated(shell) 프로세스에서 실행되는 메서드들.
package com.loopy.app.service;

interface ILoopyService {
    void destroy() = 16777114;
    void exit() = 1;

    // 하나의 스트로크(좌표 타임라인)를 주입. xs/ys = 픽셀 좌표, times = 시작기준 ms.
    // DOWN(첫 샘플) → 각 샘플 시각에 MOVE → UP(마지막). 탭/홀드/스와이프/조이스틱 통합.
    void playStroke(in int[] xs, in int[] ys, in long[] times, long durationMs) = 2;

    // MT-0 검증: 두 지점을 동시에 탭(멀티포인터 주입이 이 기기에서 되는지 확인).
    void twoFingerTapTest(int x1, int y1, int x2, int y2) = 3;
}
