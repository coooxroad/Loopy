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

    /** 현재 활성 포인터 전부를 담은 MotionEvent 하나 주입. action 은 인덱스 포함. */
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
                    // UP 시각 = 시작 + 총 지속시간(홀드 유지 재현). 마지막 샘플 시각보다 이르지 않게.
                    val lastT = timesFlat[off + cnt - 1]
                    val upT = startMs[s] + maxOf(durationsMs[s], lastT)
                    events.add(MEv(upT, 2, f, xsFlat[off + cnt - 1], ysFlat[off + cnt - 1]))
                }
                off += cnt
            }
            if (events.isEmpty()) return
            events.sortWith(compareBy({ it.time }, { it.kind }))

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
