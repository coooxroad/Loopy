package com.loopy.app.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.loopy.app.R
import com.loopy.app.core.geom.Coords
import com.loopy.app.core.io.ShizukuIo
import com.loopy.app.core.material.Material
import com.loopy.app.core.record.RecordingToTree
import com.loopy.app.data.MaterialStore
import com.loopy.app.service.LoopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 매크로/플레이리스트 컨트롤 오버레이.
 *  - 녹화: getevent → RawRecorder(좌표 타임라인). 정지 시 자동 저장.
 *  - 재생: 저장 매크로 하나, 또는 플레이리스트(셔플백 + N회/무한)를 injectInputEvent 로 주입.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private val io by lazy {
        ShizukuIo(this, scope).also {
            // 윈도우를 띄우는 일은 서비스만 할 수 있으므로 Io 가 이쪽으로 되돌아오게 연결한다.
            ShizukuIo.dimHandler = { on -> mainHandler.post { setDim(on) } }
        }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var videoEnabled = false
    private val screenRecorder by lazy { ScreenRecorder(this) }

    /** 재생은 PlaybackController 가 전담한다. */
    private val play by lazy {
        PlaybackController(
            scope = scope,
            io = io,
            materials = { id -> MaterialStore.get(this@OverlayService, id) },
            onStatus = { msg -> if (::status.isInitialized) status.text = msg },
            onRunningChanged = { on ->
                if (::stopPlayBtn.isInitialized) {
                    stopPlayBtn.visibility = if (on) View.VISIBLE else View.GONE
                }
            },
        )
    }

    /** 녹화는 RecordController 가 전담한다. 화면 갱신만 콜백으로 돌려받는다. */
    private val rec by lazy {
        RecordController(
            ctx = this,
            scope = scope,
            io = io,
            wm = wm,
            screenRecorder = screenRecorder,
            onStatus = { msg ->
                if (::status.isInitialized) { status.visibility = View.VISIBLE; status.text = msg }
            },
            onRecordingChanged = { on ->
                if (::recordBtn.isInitialized) {
                    recordBtn.setImageResource(if (on) R.drawable.ic_ov_stop else R.drawable.ic_ov_record)
                }
            },
        )
    }
    private var videoBtn: ImageButton? = null

    companion object {
        const val ACTION_START_VIDEO = "com.loopy.app.START_VIDEO"
        const val ACTION_START_MACRO_ONLY = "com.loopy.app.START_MACRO_ONLY"
        const val ACTION_SET_SESSION = "com.loopy.app.SET_SESSION"
        const val ACTION_END_SESSION = "com.loopy.app.END_SESSION"
        const val ACTION_RUN_BUILD = "com.loopy.app.RUN_BUILD"
        const val EXTRA_BUILD_ID = "build_id"

        /**
         * 빌드 실행.
         *
         * 터치 주입은 서비스에서 일어나야 한다. 앱 화면이 떠 있는 채로 재생하면 그 화면을
         * 두드리게 되므로, 실행 요청만 보내고 화면은 물러난다.
         */
        fun runBuild(ctx: android.content.Context, buildId: String) {
            ctx.startService(
                android.content.Intent(ctx, OverlayService::class.java).apply {
                    action = ACTION_RUN_BUILD
                    putExtra(EXTRA_BUILD_ID, buildId)
                },
            )
        }
        const val EX_CODE = "code"
        const val EX_DATA = "data"
    }

    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var fab: FabLogoView
    private lateinit var panel: LinearLayout
    private lateinit var listHolder: LinearLayout
    private var expanded = false
    private var hintView: TextView? = null
    private var dimView: View? = null
    private var deepLocked = false
    private lateinit var status: TextView
    private lateinit var recordBtn: ImageButton
    private lateinit var stopPlayBtn: TextView
    private var listPanel: LinearLayout? = null


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_SESSION -> setupSession()
            ACTION_END_SESSION -> {
                screenRecorder.endSession()
                VideoSession.active = false
            }
            ACTION_RUN_BUILD -> {
                val id = intent.getStringExtra(EXTRA_BUILD_ID)
                if (id != null) {
                    MaterialStore.get(this, id)?.let { playBuild(it) }
                }
            }
            ACTION_START_VIDEO -> beginVideoThenRecord(intent) // 세션 없을 때 팝업 폴백
            ACTION_START_MACRO_ONLY -> rec.start(null)
            else -> ensureOverlay() // "오버레이 켜기"(action 없음) 또는 재시작
        }
        return START_STICKY
    }

    /** 앱에서 받은 권한으로 MediaProjection 세션을 시작해 보관(재사용). */
    private fun setupSession() {
        runCatching { promoteForegroundForProjection() }
        val code = VideoSession.code
        val data = VideoSession.data
        if (code == Activity.RESULT_OK && data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
            if (proj != null) {
                screenRecorder.setSession(proj)
                VideoSession.active = true
                return
            }
        }
        VideoSession.active = false
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        LoopyService.bind(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        rec.shouldIgnore = { u, v -> if (::bar.isInitialized) barContains(u, v) else false }
        // 오버레이는 여기서 만들지 않음 — SET_SESSION 등 세션 전용 시작 시 오버레이가 안 뜨게.
        // "오버레이 켜기"(action 없는 시작)일 때만 onStartCommand 에서 생성.
    }

    private var overlayBuilt = false
    private fun ensureOverlay() {
        if (overlayBuilt) return
        buildOverlay()
        overlayBuilt = true
    }

    private fun buildOverlay() {
        // 루트: 세로. 위=[FAB + 가로 슬림 패널], 아래=상태/힌트/목록.
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            // 그림자가 창 밖으로 안 짤리게 여백 + clip 해제
            clipChildren = false
            clipToPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // 접힌 상태의 동그란 FAB
        val fabSize = dp(46)
        fab = FabLogoView(this)
        fab.elevation = dp(6).toFloat()

        // 펼치면 FAB 옆으로 길게 나오는 슬림 가로 pill
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pill(0xFFFFFFFF.toInt(), dp(22))
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        recordBtn = iconBtn(R.drawable.ic_ov_record, 0xFFFF5A4E.toInt(), 0x22FF5A4E) { toggleRecord() }
        val playBtn = iconBtn(R.drawable.ic_ov_play, 0xFF6C7BFF.toInt(), 0x226C7BFF) { playRecorded() }
        val listBtn = iconBtn(R.drawable.ic_ov_list, 0xFF3A3D55.toInt(), 0x1A3A3D55) { toggleList() }
        val moonBtn = iconBtn(R.drawable.ic_ov_moon, 0xFFE0A81E.toInt(), 0x22E0A81E) {
            toggleDeepSLock()
            if (expanded) toggleExpand()
        }
        val vBtn = iconBtn(R.drawable.ic_ov_video, videoTint(), videoBg()) { toggleVideo() }
        videoBtn = vBtn
        panel.addView(recordBtn)
        panel.addView(playBtn, marginLeft(dp(8)))
        panel.addView(vBtn, marginLeft(dp(8)))
        panel.addView(listBtn, marginLeft(dp(8)))
        panel.addView(moonBtn, marginLeft(dp(8)))

        val hRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        hRow.addView(fab, LinearLayout.LayoutParams(fabSize, fabSize))
        hRow.addView(panel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8) })

        status = TextView(this).apply {
            setTextColor(0xFF8A8DA0.toInt()); textSize = 11f; text = "녹화 버튼을 누르세요"
            setPadding(dp(6), dp(6), 0, 0)
            visibility = View.GONE
        }
        stopPlayBtn = TextView(this).apply {
            text = "■ 재생 정지"; setTextColor(0xFFFF5A4E.toInt()); textSize = 12f
            setPadding(dp(6), dp(6), 0, 0)
            visibility = View.GONE
            setOnClickListener { stopPlayback("정지됨") }
        }
        val hintTv = TextView(this).apply {
            text = "탭: 접기 · 길게 눌러 종료"
            setTextColor(0xFFB6B9C9.toInt()); textSize = 10f
            setPadding(dp(6), dp(4), 0, 0)
            visibility = View.GONE
        }
        hintView = hintTv

        // 목록 카드가 들어갈 자리 — 패널(hRow) 바로 아래, 오른쪽 정렬
        listHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }

        bar.addView(hRow)
        bar.addView(listHolder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        bar.addView(status)
        bar.addView(stopPlayBtn)
        bar.addView(hintTv)

        barParams = baseParams().apply { x = dp(-4); y = dp(64) }
        setupFabTouch()
        wm.addView(bar, barParams)
    }

    /** 원형 아이콘 버튼 — 아이콘 tint + 옅은 원형 배경으로 airy 하게. */
    /**
     * 화면 가리기.
     *
     * 윈도우를 띄우는 일만 한다. 밝기 조절이나 상태 기억은 Material 이 맡는다.
     * 그래서 사용자가 Deep SLock 빌드를 열어 "무엇을 어떤 순서로 하는지" 볼 수 있다.
     *
     * 레이어는 터치를 통과시킨다. 화면을 가린 채로도 매크로가 계속 돌아야 하기 때문이다.
     */
    /**
     * Deep SLock 토글.
     *
     * 빌드 화면이 생기면 이것은 사용자가 조립하는 블록들로 대체된다. 그때까지는
     * 기능이 동작하는 편이 낫다.
     */
    private fun toggleDeepSLock() {
        val on = !deepLocked
        setDim(on)
        scope.launch {
            if (on) {
                io.putSetting("system", "screen_brightness_mode", "0")
                io.putSetting("system", "screen_brightness", "0")
            } else {
                io.putSetting("system", "screen_brightness_mode", "1")
            }
        }
    }

    private fun setDim(on: Boolean) {
        if (on) {
            if (dimView != null) return
            val v = View(this).apply { setBackgroundColor(0xF7000000.toInt()) }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            )
            runCatching { wm.addView(v, p) }
            dimView = v
            // 가려진 뒤에도 컨트롤을 누를 수 있어야 하므로 바를 레이어 위로 다시 올린다.
            runCatching { wm.removeViewImmediate(bar); wm.addView(bar, barParams) }
        } else {
            dimView?.let { runCatching { wm.removeView(it) } }
            dimView = null
        }
        fab.setMoon(on)
        deepLocked = on
        ShizukuIo.dimState = on
    }

    /** 접기/펼치기. 펼치면 FAB 옆 슬림 패널 + 상태/힌트가 나온다. */
    private fun toggleExpand() {
        expanded = !expanded
        val vis = if (expanded) View.VISIBLE else View.GONE
        panel.visibility = vis
        status.visibility = vis
        hintView?.visibility = vis
        if (!expanded) {
            stopPlayBtn.visibility = View.GONE
            listPanel?.let { listHolder.removeView(it); listPanel = null }
        }
    }

    /** FAB: 탭=접기/펼치기, 드래그=이동, 길게=종료. */
    private fun setupFabTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val handler = Handler(Looper.getMainLooper())
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        var longFired = false
        val longPress = Runnable { longFired = true; stopSelf() }

        fab.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = barParams.x; startY = barParams.y
                    dragged = false; longFired = false
                    handler.postDelayed(longPress, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragged && hypot(dx, dy) > slop) {
                        dragged = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragged) {
                        barParams.x = startX + dx.toInt()
                        barParams.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(bar, barParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!dragged && !longFired) {
                        if (deepLocked) toggleDeepSLock() else toggleExpand()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress); true
                }
                else -> false
            }
        }
    }

    private fun marginLeft(px: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { leftMargin = px }

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    // ── 녹화 ──
    private fun videoTint() = if (videoEnabled) 0xFF20C997.toInt() else 0xFF9AA0B4.toInt()
    private fun videoBg() = if (videoEnabled) 0x2220C997 else 0x14000000

    private fun toggleVideo() {
        videoEnabled = !videoEnabled
        videoBtn?.apply {
            setColorFilter(videoTint())
            background = circleBg(videoBg())
        }
        status.visibility = View.VISIBLE
        status.text = if (videoEnabled) "영상 녹화 ON (녹화 시 화면도 저장)" else "영상 녹화 OFF"
    }

    private fun toggleRecord() {
        if (rec.isRecording) { rec.stop(); return }
        if (videoEnabled) {
            if (screenRecorder.hasSession()) {
                // 세션 재사용 — 팝업/앱전환 없이 즉시
                val ok = screenRecorder.startRecording()
                rec.start(if (ok) screenRecorder.currentUri else null)
            } else {
                // 세션 없음 → 예외적으로 팝업 1회(이후 세션으로 유지됨)
                val i = Intent(this, com.loopy.app.ProjectionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        } else {
            rec.start(null)
        }
    }

    /** 세션 없을 때 팝업 폴백: 받은 권한을 세션으로 승격 후 녹화(다음부턴 팝업 없음). */
    private fun beginVideoThenRecord(intent: Intent) {
        val code = intent.getIntExtra(EX_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EX_DATA)
        if (code != Activity.RESULT_OK || data == null) { rec.start(null); return }
        runCatching { promoteForegroundForProjection() }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (proj == null) { rec.start(null); return }
        screenRecorder.setSession(proj)
        VideoSession.active = true
        val ok = screenRecorder.startRecording()
        rec.start(if (ok) screenRecorder.currentUri else null)
    }

    private fun promoteForegroundForProjection() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1, buildNotif(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        }
    }

    // ── 재생 ──

    /** 방금 녹화한 것을 바로 재생. 저장하지 않고 트리로만 만들어 돌린다. */
    private fun playRecorded() {
        if (rec.isRecording) rec.stop()
        val snap = rec.snapshot()
        if (snap.isEmpty()) {
            status.text = "재생할 내용 없음"
            return
        }
        playBuild(RecordingToTree.build(this, snap, null, "지금 녹화"))
    }



    /**
     * 빌드 실행.
     *
     * 예전에는 셔플·사이클·간격을 여기서 직접 돌렸다. 지금은 그것들이 전부 Material 블록이므로
     * 엔진에 트리를 넘기기만 하면 된다. 새 블록이 추가돼도 이 함수는 바뀌지 않는다.
     */
    private fun playBuild(build: Material) {
        if (rec.isRecording) rec.stop()
        play.play(build)
    }

    private fun stopPlayback(msg: String?) = play.stop(msg)

    /** 스트로크들을 현재 화면 방향에 맞춰 순차 주입. 취소 가능. */
    /** 모든 스트로크를 절대 시각(startMs) 기준으로 병합해 playMulti 로 한 번에 동시 재생. */

    // ── 저장 목록 (드롭다운: 플레이리스트 + 매크로) ──
    private fun toggleList() {
        listPanel?.let { listHolder.removeView(it); listPanel = null; return }
        val builds = MaterialStore.load(this).filter { it.typeId == "build" }
        val lp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = pill(0xFFFFFFFF.toInt(), dp(18))
            elevation = dp(8).toFloat()
            minimumWidth = dp(210)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (builds.isEmpty()) {
            content.addView(hint("저장된 항목 없음"))
        } else {
            if (builds.isNotEmpty()) {
                content.addView(hint("─ 빌드 ─"))
                for (b in builds) {
                    val label = b.meta.name.ifEmpty { "빌드" }
                    content.addView(listRow("$label  ·  ${b.children.size}블록", 0xFF6C7BFF.toInt()) {
                        toggleList(); playBuild(b)
                    })
                }
            }
        }
        // 화면 절반 넘으면 스크롤
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val sv = MaxHeightScrollView(this, maxH).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        sv.addView(content)
        lp.addView(sv)
        listHolder.addView(lp, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        listPanel = lp
    }

    /** 최대 높이를 넘으면 스크롤되는 ScrollView. */
    private class MaxHeightScrollView(context: Context, private val maxH: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, h)
        }
    }


    /** 패널 좌표가 컨트롤 바 위인지. 바를 누른 터치는 매크로로 기록하지 않는다. */
    private fun barContains(u: Float, v: Float): Boolean {
        val (w, h) = io.screenSize()
        val (rx, ry) = Coords.rotate(u, v, io.rotation())
        val px = (rx * w).toInt()
        val py = (ry * h).toInt()
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height).contains(px, py)
    }


    private fun buildNotif(): Notification {
        val channelId = "loopy_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Loopy 오버레이", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Loopy 실행 중")
            .setContentText("컨트롤이 화면에 표시 중")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rec.release()
        runCatching { screenRecorder.endSession() }
        VideoSession.active = false
        scope.cancel()
        dimView?.let { runCatching { wm.removeView(it) } }
        runCatching { wm.removeView(bar) }
    }
}
