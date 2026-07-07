package com.loopy.app.service

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku UserService 본체. injectInputEvent 로 좌표 타임라인(스트로크)들을 재생한다.
 * (scrcpy 방식: InputManagerGlobal → getInstance → injectInputEvent, source=TOUCHSCREEN)
 *
 * playMulti: 여러 스트로크를 하나의 시간축에 병합해, 매 순간 활성 손가락 전부를 담은
 * 멀티포인터 MotionEvent 를 주입한다. 단일 손가락(포인터 1개)부터 멀티터치까지 통합 처리.
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

    private fun props(id: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun coords(x: Int, y: Int) = MotionEvent.PointerCoords().apply {
        this.x = x.toFloat(); this.y = y.toFloat(); pressure = 1f; size = 1f
    }

    /** 현재 활성 포인터 전부를 담은 MotionEvent 하나 주입. action 은 포인터 인덱스 포함. */
    private fun injectPointers(
        downTime: Long, action: Int, order: List<Int>,
        posX: Map<Int, Int>, posY: Map<Int, Int>,
    ) {
        val n = order.size
        if (n == 0) return
        val pp = Array(n) { props(order[it]) }
        val pc = Array(n) { coords(posX.getValue(order[it]), posY.getValue(order[it])) }
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, n, pp, pc,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        inject(ev)
        ev.recycle()
    }

    private class MEv(val time: Long, val kind: Int, val finger: Int, val x: Int, val y: Int)

    override fun playMulti(
        fingerIds: IntArray, startMs: LongArray, durationsMs: LongArray, sampleCounts: IntArray,
        xsFlat: IntArray, ysFlat: IntArray, timesFlat: LongArray,
    ) {
        runCatching {
            val shift = MotionEvent.ACTION_POINTER_INDEX_SHIFT
            val events = ArrayList<MEv>()
            var off = 0
            for (s in fingerIds.indices) {
                val cnt = sampleCounts[s]
                val f = fingerIds[s]
                for (i in 0 until cnt) {
                    val t = startMs[s] + timesFlat[off + i]
                    val kind = if (i == 0) 0 else 1 // DOWN / MOVE
                    events.add(MEv(t, kind, f, xsFlat[off + i], ysFlat[off + i]))
                }
                if (cnt > 0) {
                    // UP 시각 = 시작 + 총 지속시간(홀드 유지). 마지막 샘플 시각보다 이르지 않게.
                    val lastT = timesFlat[off + cnt - 1]
                    val upT = startMs[s] + maxOf(durationsMs[s], lastT)
                    events.add(MEv(upT, 2, f, xsFlat[off + cnt - 1], ysFlat[off + cnt - 1]))
                }
                off += cnt
            }
            if (events.isEmpty()) return
            events.sortWith(compareBy({ it.time }, { it.kind })) // 동시각: DOWN→MOVE→UP

            val order = ArrayList<Int>()
            val posX = HashMap<Int, Int>()
            val posY = HashMap<Int, Int>()
            val base = events.first().time
            val t0 = SystemClock.uptimeMillis()
            var downTime = t0

            for (ev in events) {
                val wait = (t0 + (ev.time - base)) - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
                when (ev.kind) {
                    0 -> { // DOWN
                        if (order.isEmpty()) downTime = SystemClock.uptimeMillis()
                        order.add(ev.finger)
                        posX[ev.finger] = ev.x; posY[ev.finger] = ev.y
                        val idx = order.indexOf(ev.finger)
                        val action = if (order.size == 1) MotionEvent.ACTION_DOWN
                        else MotionEvent.ACTION_POINTER_DOWN or (idx shl shift)
                        injectPointers(downTime, action, order, posX, posY)
                    }
                    1 -> { // MOVE
                        posX[ev.finger] = ev.x; posY[ev.finger] = ev.y
                        if (order.isNotEmpty()) injectPointers(downTime, MotionEvent.ACTION_MOVE, order, posX, posY)
                    }
                    2 -> { // UP
                        if (!order.contains(ev.finger)) continue
                        posX[ev.finger] = ev.x; posY[ev.finger] = ev.y
                        val idx = order.indexOf(ev.finger)
                        val action = if (order.size == 1) MotionEvent.ACTION_UP
                        else MotionEvent.ACTION_POINTER_UP or (idx shl shift)
                        injectPointers(downTime, action, order, posX, posY)
                        order.remove(ev.finger)
                    }
                }
            }
        }
    }

    override fun exit() = destroy()

    override fun destroy() {
        exitProcess(0)
    }
}
