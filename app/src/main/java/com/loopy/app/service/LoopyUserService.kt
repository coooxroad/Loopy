package com.loopy.app.service

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku UserService 본체. shell 프로세스에서 injectInputEvent 로 터치를 주입한다.
 * (scrcpy 방식: InputManagerGlobal → getInstance → injectInputEvent, source=TOUCHSCREEN)
 *
 * press(x, y, durationMs): 좌표를 durationMs 만큼 누르고 뗀다. 사용자가 실제로 누른
 * 시간을 그대로 재생하므로, 짧게 톡 친 건 짧게 / 꾹 누른 건 길게 = 원본과 동일.
 * (0ms 순간 탭은 런처/앱이 인식 못 하는 문제도 자연히 해결된다.)
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

    private fun motion(downTime: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return ev
    }

    override fun press(x: Int, y: Int, durationMs: Int) {
        runCatching {
            val t = SystemClock.uptimeMillis()
            val down = motion(t, t, MotionEvent.ACTION_DOWN, x, y)
            inject(down); down.recycle()
            // 최소 20ms 는 유지(순간탭 인식 실패 방지). 그 이상은 사용자가 누른 시간 그대로.
            Thread.sleep(durationMs.toLong().coerceAtLeast(20L))
            val t2 = SystemClock.uptimeMillis()
            val up = motion(t, t2, MotionEvent.ACTION_UP, x, y)
            inject(up); up.recycle()
        }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        runCatching {
            val steps = (durationMs / 10).coerceIn(2, 100)
            val downTime = SystemClock.uptimeMillis()
            val down = motion(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1)
            inject(down); down.recycle()
            for (i in 1..steps) {
                val f = i.toFloat() / steps
                val x = (x1 + (x2 - x1) * f).toInt()
                val y = (y1 + (y2 - y1) * f).toInt()
                Thread.sleep((durationMs / steps).toLong().coerceAtLeast(1))
                val now = SystemClock.uptimeMillis()
                val move = motion(downTime, now, MotionEvent.ACTION_MOVE, x, y)
                inject(move); move.recycle()
            }
            val end = SystemClock.uptimeMillis()
            val up = motion(downTime, end, MotionEvent.ACTION_UP, x2, y2)
            inject(up); up.recycle()
        }
    }

    override fun exit() {
        destroy()
    }

    override fun destroy() {
        exitProcess(0)
    }
}
