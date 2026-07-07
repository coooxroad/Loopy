// Shizuku UserService 인터페이스. elevated(shell) 프로세스에서 실행되는 메서드들.
package com.loopy.app.service;

interface ILoopyService {
    void destroy() = 16777114;
    void exit() = 1;

    // 하나의 스트로크(좌표 타임라인)를 주입. xs/ys = 픽셀 좌표, times = 시작기준 ms.
    // DOWN(첫 샘플) → 각 샘플 시각에 MOVE → UP(마지막). 탭/홀드/스와이프/조이스틱 통합.
    void playStroke(in int[] xs, in int[] ys, in long[] times, long durationMs) = 2;

    // MT-0 검증: 두 지점 동시 탭. 각 단계 결과/예외를 문자열로 돌려준다(진단용).
    String twoFingerTapTest(int x1, int y1, int x2, int y2) = 3;

    // MT-1: 여러 스트로크를 시간축에 병합해 동시 재생(멀티터치).
    // 각 스트로크의 샘플을 평탄 배열로 이어붙이고 sampleCounts 로 경계를 나눈다.
    //  fingerIds[s]    = 스트로크 s 의 손가락 id
    //  startMs[s]      = 스트로크 s 의 절대 시작 시각(전체 기준 ms)
    //  durationsMs[s]  = 스트로크 s 의 down→up 총 지속시간(홀드 유지 재현)
    //  sampleCounts[s] = 스트로크 s 의 샘플 수
    //  xsFlat/ysFlat   = 모든 샘플의 픽셀 좌표(순서대로)
    //  timesFlat       = 각 샘플의 "스트로크 시작 기준" ms
    void playMulti(in int[] fingerIds, in long[] startMs, in long[] durationsMs, in int[] sampleCounts,
                   in int[] xsFlat, in int[] ysFlat, in long[] timesFlat) = 4;
}
