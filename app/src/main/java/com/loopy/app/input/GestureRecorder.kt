package com.loopy.app.input

import android.os.SystemClock
import java.util.Collections
import kotlin.math.hypot

/**
 * getevent 로 읽은 터치 포인트(panel 정규화 0~1)를 손가락 down→up 단위로 보고
 * 탭 / 홀드 / 스와이프로 판정해 Action 리스트로 쌓는다.
 *
 * 좌표는 panel 정규화(u,v) 그대로 저장한다. 화면 픽셀 변환(회전 보정)은 재생 쪽에서
 * 현재 방향에 맞춰 수행하므로, 저장은 방향 독립적이다.
 *
 * 판정 기준:
 *  - 이동거리 >= MOVE_THRESH  → 스와이프 (시작→끝, 걸린 시간)
 *  - 그 외 누른 시간 >= HOLD_THRESH_MS → 홀드 (좌표, 시간)
 *  - 그 외 → 탭
 */
class GestureRecorder {

    enum class Type { TAP, HOLD, SWIPE }

    data class Action(
        val delayMs: Long,      // 이전 행동이 끝난 뒤 이 행동 시작까지 대기
        val type: Type,
        val x: Float, val y: Float,     // 시작(panel 0~1)
        val x2: Float, val y2: Float,   // 스와이프 끝
        val durationMs: Long,           // 홀드/스와이프 지속
    )

    val actions: MutableList<Action> = Collections.synchronizedList(mutableListOf())

    /** panel 좌표(u,v)가 무시 대상(예: 컨트롤 바 위)인지. 재생/판정 쪽에서 주입. */
    var shouldIgnore: (Float, Float) -> Boolean = { _, _ -> false }

    private var down = false
    private var downT = 0L
    private var downX = 0f
    private var downY = 0f
    private var curX = 0f
    private var curY = 0f
    private var lastEndT = 0L

    fun reset() {
        actions.clear()
        down = false
        lastEndT = 0L
    }

    fun onPoint(p: TouchPoint) {
        val now = SystemClock.uptimeMillis()
        when {
            p.down && !down -> { // 손가락 내려감
                down = true
                downT = now
                downX = p.nx; downY = p.ny
                curX = p.nx; curY = p.ny
            }
            p.down -> { // 이동 중
                curX = p.nx; curY = p.ny
            }
            !p.down && down -> { // 손가락 뗌 → 판정
                down = false
                if (shouldIgnore(downX, downY)) {
                    lastEndT = now
                    return
                }
                val dur = now - downT
                val dist = hypot((curX - downX).toDouble(), (curY - downY).toDouble()).toFloat()
                val delay = if (actions.isEmpty()) 0L else (downT - lastEndT).coerceAtLeast(0L)
                val a = when {
                    dist >= MOVE_THRESH ->
                        Action(delay, Type.SWIPE, downX, downY, curX, curY, dur)
                    dur >= HOLD_THRESH_MS ->
                        Action(delay, Type.HOLD, downX, downY, 0f, 0f, dur)
                    else ->
                        Action(delay, Type.TAP, downX, downY, 0f, 0f, 0L)
                }
                actions.add(a)
                lastEndT = now
            }
        }
    }

    companion object {
        // panel 정규화 기준 이동거리 임계(≈ 화면 3%). 넘으면 스와이프.
        const val MOVE_THRESH = 0.03f
        // 이 시간 이상 누르면 홀드로 본다.
        const val HOLD_THRESH_MS = 400L
    }
}
