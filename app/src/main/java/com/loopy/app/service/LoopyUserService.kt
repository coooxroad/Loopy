package com.loopy.app.service

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku UserService 본체. Shizuku 가 이 클래스를 shell(uid 2000) 프로세스에 띄운다.
 * 그 프로세스는 shell 권한이라, adb 의 `input` 명령과 동일하게 injectInputEvent 를
 * 호출할 수 있다(앱 프로세스에서는 권한이 없어 불가).
 *
 * 주입 방식은 scrcpy 와 동일:
 *  - 안드로이드 14+ 에서 일부가 InputManagerGlobal 로 옮겨졌으므로 그것을 먼저 시도,
 *    없으면 InputManager 로 폴백.
 *  - getInstance() 로 인스턴스를 얻고 injectInputEvent(InputEvent, int) 를 리플렉션 호출.
 *  - MotionEvent 의 source 를 TOUCHSCREEN 으로 지정해야 터치로 인식된다.
 */
class LoopyUserService : ILoopyService.Stub {

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
        // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        injectMethod.invoke(instance, ev, 0)
    }

    private fun motion(downTime: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return ev
    }

    private fun downUp(x: Int, y: Int) {
        val t = SystemClock.uptimeMillis()
        val down = motion(t, t, MotionEvent.ACTION_DOWN, x, y)
        inject(down); down.recycle()
        val t2 = SystemClock.uptimeMillis()
        val up = motion(t, t2, MotionEvent.ACTION_UP, x, y)
        inject(up); up.recycle()
    }

    override fun tap(x: Int, y: Int) {
        runCatching { downUp(x, y) }
    }

    override fun doubleTap(x: Int, y: Int, gapMs: Int) {
        runCatching {
            downUp(x, y)
            Thread.sleep(gapMs.toLong().coerceAtLeast(0))
            downUp(x, y)
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
