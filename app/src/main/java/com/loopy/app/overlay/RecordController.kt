package com.loopy.app.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import com.loopy.app.core.io.Io
import com.loopy.app.core.record.Recording
import com.loopy.app.core.record.RecordingStore
import com.loopy.app.core.record.RecordingToTree
import com.loopy.app.core.record.RotationEvent
import com.loopy.app.data.MaterialStore
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.RawRecorder
import com.loopy.app.input.TouchDevice
import kotlinx.coroutines.CoroutineScope

/**
 * 녹화 한 판을 책임진다 — 터치 스트림, 화면 회전 이력, 영상 싱크, 그리고 결과를 Material 트리로 저장.
 *
 * OverlayService 가 수명주기·오버레이·재생까지 한꺼번에 맡아 763줄이었다. 그중 "녹화"만 여기로 뺀다.
 * 화면(상태 문구·버튼 아이콘)은 콜백으로 알린다 — 이 클래스는 View 를 모른다.
 */
class RecordController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val io: Io,
    private val wm: WindowManager,
    private val screenRecorder: ScreenRecorder,
    private val onStatus: (String) -> Unit,
    private val onRecordingChanged: (Boolean) -> Unit,
) {
    private val reader = GeteventReader()
    private val recorder = RawRecorder()
    private var device: TouchDevice? = null
    private val rotEventsRaw = ArrayList<Pair<Long, Int>>()
    private var rotListener: DisplayManager.DisplayListener? = null
    private var currentVideoPath: String? = null

    private val displayObj by lazy {
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
    }

    var isRecording = false
        private set

    /** 컨트롤 바 위 터치는 매크로로 기록하지 않는다. 판정은 서비스(뷰를 아는 쪽)가 넣어준다. */
    var shouldIgnore: (Float, Float) -> Boolean
        get() = recorder.shouldIgnore
        set(value) { recorder.shouldIgnore = value }

    /** 방금 녹화한 것(저장 전)의 스냅샷. 즉시 재생에 쓴다. */
    fun snapshot() = recorder.snapshot()

    /** 녹화 시작. 터치 장치를 못 찾으면 상태만 알리고 시작하지 않는다. */
    fun start(videoPath: String?) {
        val devs = reader.probe()
        val dev = devs.firstOrNull { it.name.contains("touchscreen", true) } ?: devs.firstOrNull()
        if (dev == null) { onStatus("터치 장치 없음"); return }
        device = dev
        currentVideoPath = videoPath
        recorder.reset()
        recorder.currentRotation = { io.rotation() }
        isRecording = true
        rotEventsRaw.clear()
        registerRotationListener()
        onRecordingChanged(true)
        onStatus(if (videoPath != null) "● 녹화 중 · 영상" else "● 녹화 중")
        reader.stream(scope, listOf(dev)) { _, p -> recorder.onPoint(p) }
    }

    /** 녹화 정지 → 회전 이력·영상 싱크를 붙여 Material 트리로 저장. */
    fun stop() {
        reader.stop()
        isRecording = false
        unregisterRotationListener()
        onRecordingChanged(false)
        val videoStart = if (screenRecorder.active) screenRecorder.startUptime else 0L
        val vpath = if (screenRecorder.active) screenRecorder.stopRecording() else currentVideoPath
        currentVideoPath = null
        val snap = recorder.snapshot()
        if (snap.isEmpty()) {
            onStatus("행동 없음 (저장 안 함)")
            return
        }
        val offset = if (vpath != null && videoStart > 0 && recorder.baseUptime > 0)
            (recorder.baseUptime - videoStart).coerceAtLeast(0L) else 0L
        val rot = runCatching {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.rotation * 90
        }.getOrDefault(0)
        // 회전 이벤트: 절대 uptime → 매크로 시작(baseUptime) 기준 상대 ms
        val base = recorder.baseUptime
        val startRot = rotEventsRaw.lastOrNull { it.first <= base }?.second ?: rot
        val evts = ArrayList<RotationEvent>()
        evts.add(RotationEvent(0L, startRot))
        if (base > 0) {
            for ((up, r) in rotEventsRaw) {
                val rel = up - base
                if (rel > 0L) evts.add(RotationEvent(rel, r))
            }
        }
        // 녹화 결과를 Material 트리로 바꾼다. 시각은 순서와 대기가 되고, 겹친 궤적은 parallel 이 된다.
        val recId = RecordingStore.newId()
        RecordingStore.put(
            ctx,
            Recording(id = recId, videoPath = vpath, videoOffsetMs = offset, rotationEvents = evts),
        )
        val name = autoName()
        val build = RecordingToTree.build(ctx, snap, recId, name)
        MaterialStore.upsert(ctx, build)
        onStatus("저장됨: $name · ${snap.size}개" + if (vpath != null) " · 영상" else "")
    }

    /** 서비스 종료 시 스트림만 정리. */
    fun release() {
        reader.stop()
        unregisterRotationListener()
    }

    private fun autoName(): String {
        val f = java.text.SimpleDateFormat("MMM d a h:mm", java.util.Locale.getDefault())
        return f.format(java.util.Date())
    }

    /** 녹화 중 화면 회전 변화를 타임스탬프와 함께 기록. */
    private fun registerRotationListener() {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        var lastRot = displayObj.rotation * 90
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (!isRecording || displayId != Display.DEFAULT_DISPLAY) return
                val r = displayObj.rotation * 90
                if (r == lastRot) return
                lastRot = r
                rotEventsRaw.add(SystemClock.uptimeMillis() to r)
            }
        }
        dm.registerDisplayListener(l, null)
        rotListener = l
    }

    private fun unregisterRotationListener() {
        rotListener?.let {
            runCatching {
                (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .unregisterDisplayListener(it)
            }
        }
        rotListener = null
    }
}
