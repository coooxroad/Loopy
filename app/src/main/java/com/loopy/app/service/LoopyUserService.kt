package com.loopy.app.service

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku UserService 본체. injectInputEvent 로 좌표 타임라인(스트로크)을 재생한다.
 * (scrcpy 방식: InputManagerGlobal → getInstance → injectInputEvent, source=TOUCHSCREEN)
 *
 * playStroke: 첫 샘플에서 DOWN, 각 샘플의 times[i](ms)에 맞춰 MOVE, 마지막에 UP.
 * 사용자가 그린 경로와 시간을 그대로 재현하므로 탭/홀드/스와이프/조이스틱이 모두 됨.
 */
class LoopyUserService : ILoopyService.Stub() {

    private val instance: Any
    private val injectMethod: Method

    init {
        val cls = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }
            .getOrElse { Class.forName("android.hardware.input.InputManager") }
        val getInstance = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }
        instance = getInstance.invoke(null)!!
        injectMethod = runCatching {
            instance.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        }.getOrElse {
            instance.javaClass.getDeclaredMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        }.apply { isAccessible = true }
    }

    private fun inject(ev: InputEvent) {
        injectMethod.invoke(instance, ev, 0) // 0 = ASYNC
    }

    private fun send(downTime: Long, action: Int, x: Int, y: Int) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        inject(ev)
        ev.recycle()
    }

    // ── 멀티포인터 주입 (MT-0) ──
    private fun props(id: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun coords(x: Int, y: Int) = MotionEvent.PointerCoords().apply {
        this.x = x.toFloat(); this.y = y.toFloat(); pressure = 1f; size = 1f
    }

    /** 여러 포인터를 담은 MotionEvent 하나를 주입. ids/xs/ys 는 같은 길이. */
    private fun injectMulti(downTime: Long, action: Int, ids: IntArray, xs: IntArray, ys: IntArray) {
        val n = ids.size
        val pp = Array(n) { props(ids[it]) }
        val pc = Array(n) { coords(xs[it], ys[it]) }
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, n, pp, pc,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        inject(ev)
        ev.recycle()
    }

    override fun twoFingerTapTest(x1: Int, y1: Int, x2: Int, y2: Int): String {
        val sb = StringBuilder()
        try {
            val dt = SystemClock.uptimeMillis()
            val idxShift = MotionEvent.ACTION_POINTER_INDEX_SHIFT
            injectMulti(dt, MotionEvent.ACTION_DOWN, intArrayOf(0), intArrayOf(x1), intArrayOf(y1))
            sb.append("D0ok ")
            Thread.sleep(8)
            injectMulti(
                dt, MotionEvent.ACTION_POINTER_DOWN or (1 shl idxShift),
                intArrayOf(0, 1), intArrayOf(x1, x2), intArrayOf(y1, y2),
            )
            sb.append("D1ok ")
            Thread.sleep(120)
            injectMulti(
                dt, MotionEvent.ACTION_POINTER_UP or (1 shl idxShift),
                intArrayOf(0, 1), intArrayOf(x1, x2), intArrayOf(y1, y2),
            )
            sb.append("U1ok ")
            Thread.sleep(8)
            injectMulti(dt, MotionEvent.ACTION_UP, intArrayOf(0), intArrayOf(x1), intArrayOf(y1))
            sb.append("U0ok")
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            sb.append("ERR:").append(cause.javaClass.simpleName)
                .append(":").append((cause.message ?: "").take(70))
        }
        return sb.toString()
    }

    override fun playStroke(xs: IntArray, ys: IntArray, times: LongArray, durationMs: Long) {
        runCatching {
            val n = xs.size
            if (n == 0) return
            val downTime = SystemClock.uptimeMillis()
            send(downTime, MotionEvent.ACTION_DOWN, xs[0], ys[0])
            for (i in 1 until n) {
                val wait = (downTime + times[i]) - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
                send(downTime, MotionEvent.ACTION_MOVE, xs[i], ys[i])
            }
            // 마지막 샘플 후, down→up 총 지속시간(durationMs)이 될 때까지 유지(홀드 재현).
            // 최소 20ms 는 보장(순간탭 인식 실패 방지).
            val upTarget = downTime + durationMs.coerceAtLeast(20L)
            val remain = upTarget - SystemClock.uptimeMillis()
            if (remain > 0) Thread.sleep(remain)
            send(downTime, MotionEvent.ACTION_UP, xs[n - 1], ys[n - 1])
        }
    }

    override fun exit() = destroy()

    override fun destroy() {
        exitProcess(0)
    }
}
