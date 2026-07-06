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
