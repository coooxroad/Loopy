#!/data/data/com.termux/files/usr/bin/bash
# Loopy: 플레이리스트(패턴+셔플백+무한/N회) + 편집기
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/macro/Playlist.kt")"
cat > "app/src/main/java/com/loopy/app/macro/Playlist.kt" << 'LOOPY_EOF'
package com.loopy.app.macro

/**
 * 매크로들을 엮은 플레이리스트.
 *  - macroIds: 패턴 순서(중복 가능). 예: [A, A, B, C]
 *  - shuffle: 켜면 매 사이클마다 그 패턴을 섞어서(셔플백) 재생
 *  - cycles: 반복 횟수. 0 = 무한
 */
data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val macroIds: List<String>,
    val shuffle: Boolean,
    val cycles: Int,
)
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/macro/PlaylistStore.kt")"
cat > "app/src/main/java/com/loopy/app/macro/PlaylistStore.kt" << 'LOOPY_EOF'
package com.loopy.app.macro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 플레이리스트를 filesDir/playlists/{id}.json 로 저장/관리. */
object PlaylistStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "playlists").apply { mkdirs() }

    fun save(
        ctx: Context,
        name: String,
        macroIds: List<String>,
        shuffle: Boolean,
        cycles: Int,
        existingId: String? = null,
    ): Playlist {
        val pl = Playlist(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            macroIds = macroIds,
            shuffle = shuffle,
            cycles = cycles,
        )
        File(dir(ctx), "${pl.id}.json").writeText(toJson(pl))
        return pl
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    fun list(ctx: Context): List<Playlist> =
        (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun read(ctx: Context, id: String): Playlist? =
        runCatching { fromJson(File(dir(ctx), "$id.json").readText()) }.getOrNull()

    private fun toJson(p: Playlist): String {
        val ids = JSONArray()
        p.macroIds.forEach { ids.put(it) }
        return JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("createdAt", p.createdAt)
            .put("macroIds", ids)
            .put("shuffle", p.shuffle)
            .put("cycles", p.cycles)
            .toString()
    }

    private fun fromJson(text: String): Playlist {
        val o = JSONObject(text)
        val arr = o.getJSONArray("macroIds")
        val ids = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) ids.add(arr.getString(i))
        return Playlist(
            id = o.getString("id"),
            name = o.getString("name"),
            createdAt = o.getLong("createdAt"),
            macroIds = ids,
            shuffle = o.getBoolean("shuffle"),
            cycles = o.getInt("cycles"),
        )
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/overlay/OverlayService.kt")"
cat > "app/src/main/java/com/loopy/app/overlay/OverlayService.kt" << 'LOOPY_EOF'
package com.loopy.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.loopy.app.input.GestureRecorder
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.TouchDevice
import com.loopy.app.macro.Macro
import com.loopy.app.macro.MacroStore
import com.loopy.app.macro.Playlist
import com.loopy.app.macro.PlaylistStore
import com.loopy.app.service.LoopyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 매크로/플레이리스트 컨트롤 오버레이.
 *  - 녹화: getevent → GestureRecorder. 정지 시 자동 저장.
 *  - 재생: 저장 매크로 하나, 또는 플레이리스트(셔플백 + N회/무한)를 injectInputEvent 로 주입.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private val reader = GeteventReader()
    private val recorder = GestureRecorder()
    private var device: TouchDevice? = null
    private var recording = false
    private var playJob: Job? = null

    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var recordBtn: Button
    private lateinit var stopPlayBtn: TextView
    private var listPanel: LinearLayout? = null

    private val displayObj by lazy {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        LoopyService.bind(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        recorder.shouldIgnore = { u, v -> barContains(u, v) }
        addControlBar()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun addControlBar() {
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = pill(0xF2FFFFFF.toInt(), dp(18))
            elevation = dp(6).toFloat()
        }
        val title = TextView(this).apply {
            text = "Loopy"; setTextColor(0xFF2B2D42.toInt()); textSize = 15f
        }
        status = TextView(this).apply {
            setTextColor(0xFF8A8DA0.toInt()); textSize = 11f; text = "녹화를 눌러 시작"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordBtn = pillButton("● 녹화", 0xFFFF7A6E.toInt()) { toggleRecord() }
        val playBtn = pillButton("▶ 재생", 0xFF6C7BFF.toInt()) { playRecorded() }
        val listBtn = pillButton("📁", 0xFFECECF2.toInt(), 0xFF2B2D42.toInt()) { toggleList() }
        row.addView(recordBtn)
        row.addView(playBtn, marginLeft(dp(8)))
        row.addView(listBtn, marginLeft(dp(8)))

        stopPlayBtn = TextView(this).apply {
            text = "■ 재생 정지"; setTextColor(0xFFFF5A4E.toInt()); textSize = 12f
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
            setOnClickListener { stopPlayback("정지됨") }
        }
        val closeBtn = TextView(this).apply {
            text = "닫기"; setTextColor(0xFF8A8DA0.toInt()); textSize = 11f
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { stopSelf() }
        }

        bar.addView(title)
        bar.addView(status)
        bar.addView(row)
        bar.addView(stopPlayBtn)
        bar.addView(closeBtn)

        barParams = baseParams().apply { x = dp(12); y = dp(60) }
        makeDraggable(title)
        wm.addView(bar, barParams)
    }

    private fun pillButton(label: String, bg: Int, fg: Int = 0xFFFFFFFF.toInt(), onClick: () -> Unit) =
        Button(this).apply {
            text = label; setTextColor(fg); background = pill(bg, dp(12))
            setOnClickListener { onClick() }
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
    private fun toggleRecord() {
        if (!recording) startRecord() else stopRecord()
    }

    private fun startRecord() {
        stopPlayback(null)
        val devs = reader.probe()
        val dev = devs.firstOrNull { it.name.contains("touchscreen", true) } ?: devs.firstOrNull()
        if (dev == null) { status.text = "터치 디바이스를 못 찾음"; return }
        device = dev
        recorder.reset()
        recording = true
        recordBtn.text = "■ 정지"
        status.text = "● 녹화중 — 평소처럼 플레이해"
        reader.stream(scope, listOf(dev)) { _, p -> recorder.onPoint(p) }
    }

    private fun stopRecord() {
        reader.stop()
        recording = false
        recordBtn.text = "● 녹화"
        val snap = recorder.snapshot()
        if (snap.isEmpty()) { status.text = "행동 없음 (저장 안 함)"; return }
        val m = MacroStore.saveNew(this, snap)
        status.text = "저장됨: ${m.name} · ${snap.size}개"
    }

    // ── 재생 ──
    private fun playRecorded() {
        if (recording) stopRecord()
        startSingle(recorder.snapshot(), "지금 녹화")
    }

    private fun startSingle(list: List<GestureRecorder.Action>, label: String) {
        if (recording) stopRecord()
        stopPlayback(null)
        if (list.isEmpty()) { status.text = "재생할 행동이 없어"; return }
        stopPlayBtn.visibility = View.VISIBLE
        status.text = "▶ 재생중… $label"
        playJob = scope.launch {
            runActions(list)
            status.text = "재생 끝 · $label"
            stopPlayBtn.visibility = View.GONE
            playJob = null
        }
    }

    private fun playPlaylist(pl: Playlist) {
        if (recording) stopRecord()
        stopPlayback(null)
        val macros = HashMap<String, Macro>()
        pl.macroIds.toSet().forEach { id -> MacroStore.read(this, id)?.let { macros[id] = it } }
        if (macros.isEmpty()) { status.text = "매크로가 비어있어"; return }
        stopPlayBtn.visibility = View.VISIBLE
        playJob = scope.launch {
            var cycle = 0
            while (isActive && (pl.cycles == 0 || cycle < pl.cycles)) {
                val order = if (pl.shuffle) pl.macroIds.shuffled() else pl.macroIds
                for (id in order) {
                    if (!isActive) break
                    val m = macros[id] ?: continue
                    val total = if (pl.cycles > 0) "/${pl.cycles}" else ""
                    status.text = "▶ ${pl.name} · ${cycle + 1}$total · ${m.name}"
                    runActions(m.actions)
                }
                cycle++
            }
            status.text = "플레이리스트 끝 · ${pl.name}"
            stopPlayBtn.visibility = View.GONE
            playJob = null
        }
    }

    private fun stopPlayback(msg: String?) {
        playJob?.cancel()
        playJob = null
        stopPlayBtn.visibility = View.GONE
        if (msg != null) status.text = msg
    }

    /** 한 매크로의 액션들을 현재 화면 방향에 맞춰 순차 주입. 취소 가능(delay). */
    private suspend fun runActions(list: List<GestureRecorder.Action>) {
        val m = DisplayMetrics()
        displayObj.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels
        val rot = displayObj.rotation
        for (a in list) {
            if (!coroutineContext.isActive) return
            delay(a.delayMs)
            val (x, y) = toPx(a.x, a.y, w, h, rot)
            withContext(Dispatchers.IO) {
                when (a.type) {
                    GestureRecorder.Type.TAP -> LoopyService.tap(x, y)
                    GestureRecorder.Type.HOLD -> LoopyService.hold(x, y, a.durationMs.toInt())
                    GestureRecorder.Type.SWIPE -> {
                        val (x2, y2) = toPx(a.x2, a.y2, w, h, rot)
                        LoopyService.swipe(x, y, x2, y2, a.durationMs.toInt().coerceAtLeast(50))
                    }
                }
            }
        }
    }

    // ── 저장 목록 (드롭다운: 플레이리스트 + 매크로) ──
    private fun toggleList() {
        listPanel?.let { bar.removeView(it); listPanel = null; return }
        val playlists = PlaylistStore.list(this)
        val macros = MacroStore.list(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0)
        }
        if (playlists.isEmpty() && macros.isEmpty()) {
            panel.addView(hint("저장된 게 없어"))
        } else {
            if (playlists.isNotEmpty()) {
                panel.addView(hint("─ 플레이리스트 ─"))
                for (pl in playlists) {
                    panel.addView(listRow("▶▶ ${pl.name} (${pl.macroIds.size})", 0xFF6C7BFF.toInt()) {
                        toggleList(); playPlaylist(pl)
                    })
                }
            }
            if (macros.isNotEmpty()) {
                panel.addView(hint("─ 매크로 ─"))
                for (mac in macros) {
                    panel.addView(listRow("▶ ${mac.name} (${mac.actions.size})", 0xFF2B2D42.toInt()) {
                        toggleList(); startSingle(mac.actions, mac.name)
                    })
                }
            }
        }
        bar.addView(panel)
        listPanel = panel
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF8A8DA0.toInt()); textSize = 10f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun listRow(t: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(color); textSize = 12f
        setPadding(0, dp(7), 0, dp(7))
        setOnClickListener { onClick() }
    }

    private fun toPx(u: Float, v: Float, w: Int, h: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> (v * w).toInt() to ((1 - u) * h).toInt()
        Surface.ROTATION_180 -> ((1 - u) * w).toInt() to ((1 - v) * h).toInt()
        Surface.ROTATION_270 -> ((1 - v) * w).toInt() to (u * h).toInt()
        else -> (u * w).toInt() to (v * h).toInt()
    }

    private fun barContains(u: Float, v: Float): Boolean {
        val m = DisplayMetrics()
        displayObj.getRealMetrics(m)
        val (px, py) = toPx(u, v, m.widthPixels, m.heightPixels, displayObj.rotation)
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height).contains(px, py)
    }

    private fun makeDraggable(handle: View) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = barParams.x; startY = barParams.y; touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    barParams.x = startX + (e.rawX - touchX).toInt()
                    barParams.y = startY + (e.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(bar, barParams) }; true
                }
                else -> false
            }
        }
    }

    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }

    private fun startAsForeground() {
        val channelId = "loopy_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Loopy 오버레이", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Loopy 실행 중")
            .setContentText("매크로 컨트롤이 화면에 떠 있어요")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reader.stop()
        scope.cancel()
        runCatching { wm.removeView(bar) }
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/MainActivity.kt")"
cat > "app/src/main/java/com/loopy/app/MainActivity.kt" << 'LOOPY_EOF'
package com.loopy.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.macro.Macro
import com.loopy.app.macro.MacroStore
import com.loopy.app.macro.Playlist
import com.loopy.app.macro.PlaylistStore
import com.loopy.app.overlay.OverlayService
import com.loopy.app.service.LoopyService
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.GlassCard
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.ui.theme.LoopyTheme
import com.loopy.app.ui.theme.MeshGradientBackground
import com.loopy.app.ui.theme.MeshLavender
import com.loopy.app.ui.theme.MeshMint
import com.loopy.app.ui.theme.MeshPeach
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val binderListener = Shizuku.OnBinderReceivedListener { onShizukuChanged?.invoke() }
    private val deadListener = Shizuku.OnBinderDeadListener { onShizukuChanged?.invoke() }
    private var onShizukuChanged: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        setContent { LoopyTheme { RootScreen(registerRefresh = { cb -> onShizukuChanged = cb }) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
    }
}

@Composable
private fun RootScreen(registerRefresh: ((() -> Unit)) -> Unit) {
    val context = LocalContext.current

    var state by remember { mutableStateOf(ShizukuManager.state()) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var msg by remember { mutableStateOf("오버레이를 켜고 로블록스로 전환한 뒤, 컨트롤 바에서 녹화/재생.") }
    var macros by remember { mutableStateOf(MacroStore.list(context)) }
    var playlists by remember { mutableStateOf(PlaylistStore.list(context)) }
    var renaming by remember { mutableStateOf<Macro?>(null) }
    var nameField by remember { mutableStateOf("") }

    // 편집기 상태
    var editorOpen by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var plName by remember { mutableStateOf("") }
    var plShuffle by remember { mutableStateOf(false) }
    var plCycles by remember { mutableStateOf("") }
    val pattern = remember { mutableStateListOf<String>() }
    var editorMacros by remember { mutableStateOf<List<Macro>>(emptyList()) }

    fun refresh() {
        macros = MacroStore.list(context)
        playlists = PlaylistStore.list(context)
    }

    fun openEditor(pl: Playlist?) {
        editorMacros = MacroStore.list(context)
        editId = pl?.id
        plName = pl?.name ?: ""
        plShuffle = pl?.shuffle ?: false
        plCycles = pl?.cycles?.takeIf { it > 0 }?.toString() ?: ""
        pattern.clear()
        pl?.macroIds?.let { pattern.addAll(it) }
        editorOpen = true
    }

    LaunchedEffect(Unit) {
        registerRefresh {
            state = ShizukuManager.state()
            canOverlay = Settings.canDrawOverlays(context)
            refresh()
        }
    }
    LaunchedEffect(state) { if (state == ShizukuState.READY) LoopyService.bind(context) }

    if (editorOpen) {
        PlaylistEditor(
            name = plName, onName = { plName = it },
            shuffle = plShuffle, onShuffle = { plShuffle = it },
            cycles = plCycles, onCycles = { plCycles = it.filter { c -> c.isDigit() } },
            pattern = pattern,
            macros = editorMacros,
            onSave = {
                if (plName.isNotBlank() && pattern.isNotEmpty()) {
                    PlaylistStore.save(
                        context, plName.trim(), pattern.toList(),
                        plShuffle, plCycles.toIntOrNull() ?: 0, editId,
                    )
                    refresh()
                    editorOpen = false
                }
            },
            onCancel = { editorOpen = false },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        MeshGradientBackground(animate = true)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Loopy", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("레코드 매크로", color = TextLo, fontSize = 14.sp)

            GlassCard(Modifier.fillMaxWidth()) {
                Text("1. Shizuku", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    when (state) {
                        ShizukuState.NOT_INSTALLED -> "연결 안 됨 · Shizuku 앱 실행 필요"
                        ShizukuState.NEEDS_PERMISSION -> "설치됨 · 권한 허용 필요"
                        ShizukuState.READY -> "준비 완료"
                    },
                    color = if (state == ShizukuState.READY) Accent else TextLo, fontSize = 13.sp,
                )
                if (state == ShizukuState.NEEDS_PERMISSION) {
                    Spacer(Modifier.height(12.dp))
                    LoopyButton("권한 허용") {
                        ShizukuManager.requestPermission { g ->
                            state = if (g) ShizukuState.READY else ShizukuState.NEEDS_PERMISSION
                        }
                    }
                } else if (state == ShizukuState.NOT_INSTALLED) {
                    Spacer(Modifier.height(12.dp))
                    LoopyButton("다시 확인") { state = ShizukuManager.state() }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("2. 오버레이", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(if (canOverlay) "권한 허용됨" else "다른 앱 위에 표시 권한 필요",
                    color = if (canOverlay) Accent else TextLo, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(msg, color = TextLo, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                if (!canOverlay) {
                    LoopyButton("권한 설정 열기") {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LoopyButton("권한 상태 새로고침", filled = false) { canOverlay = Settings.canDrawOverlays(context) }
                } else {
                    LoopyButton("오버레이 켜기") {
                        context.startForegroundService(Intent(context, OverlayService::class.java))
                        msg = "켜졌어! 로블록스로 전환 → 녹화/재생, 📁 목록."
                    }
                    Spacer(Modifier.height(8.dp))
                    LoopyButton("오버레이 끄기", filled = false) {
                        context.stopService(Intent(context, OverlayService::class.java))
                        msg = "오버레이를 껐어."
                    }
                }
            }

            // ── 플레이리스트 ──
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("3. 플레이리스트", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("+ 새로 만들기", color = Accent, fontSize = 12.sp, modifier = Modifier.clickable { openEditor(null) })
                }
                if (playlists.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("매크로 2개 이상 저장한 뒤 만들어봐.", color = TextLo, fontSize = 12.sp)
                } else {
                    playlists.forEach { pl ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                val rep = if (pl.cycles == 0) "무한" else "${pl.cycles}회"
                                val sh = if (pl.shuffle) " · 셔플" else ""
                                Text("${pl.macroIds.size}스텝 · $rep$sh", color = TextLo, fontSize = 11.sp)
                            }
                            Text("편집", color = Accent, fontSize = 12.sp, modifier = Modifier.clickable { openEditor(pl) })
                            Spacer(Modifier.width(14.dp))
                            Text("삭제", color = TextLo, fontSize = 12.sp,
                                modifier = Modifier.clickable { PlaylistStore.delete(context, pl.id); refresh() })
                        }
                    }
                }
            }

            // ── 저장된 매크로 ──
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("4. 저장된 매크로", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("새로고침", color = Accent, fontSize = 12.sp, modifier = Modifier.clickable { refresh() })
                }
                if (macros.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("아직 없어. 오버레이에서 녹화하면 여기 쌓여.", color = TextLo, fontSize = 12.sp)
                } else {
                    macros.forEach { m ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("${m.actions.size}개 행동", color = TextLo, fontSize = 11.sp)
                            }
                            Text("이름변경", color = Accent, fontSize = 12.sp,
                                modifier = Modifier.clickable { renaming = m; nameField = m.name })
                            Spacer(Modifier.width(14.dp))
                            Text("삭제", color = TextLo, fontSize = 12.sp,
                                modifier = Modifier.clickable { MacroStore.delete(context, m.id); refresh() })
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    val editing = renaming
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                TextButton(onClick = {
                    if (nameField.isNotBlank()) MacroStore.rename(context, editing.id, nameField.trim())
                    renaming = null; refresh()
                }) { Text("저장", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소", color = TextLo) } },
            title = { Text("이름 변경", color = TextHi) },
            text = { OutlinedTextField(value = nameField, onValueChange = { nameField = it }, singleLine = true) },
            containerColor = LoopyCard,
        )
    }
}

@Composable
private fun PlaylistEditor(
    name: String, onName: (String) -> Unit,
    shuffle: Boolean, onShuffle: (Boolean) -> Unit,
    cycles: String, onCycles: (String) -> Unit,
    pattern: MutableList<String>,
    macros: List<Macro>,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    fun macroName(id: String) = macros.firstOrNull { it.id == id }?.name ?: "(삭제됨)"
    Box(Modifier.fillMaxSize()) {
        MeshGradientBackground(animate = false)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("플레이리스트 편집", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold)

            GlassCard(Modifier.fillMaxWidth()) {
                Text("이름", color = TextLo, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = name, onValueChange = onName, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("셔플 (매 사이클 섞기)", color = TextHi, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = shuffle, onCheckedChange = onShuffle)
                }
                Spacer(Modifier.height(10.dp))
                Text("반복 횟수 (비우면 무한)", color = TextLo, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = cycles, onValueChange = onCycles, singleLine = true,
                    placeholder = { Text("무한") }, modifier = Modifier.width(140.dp))
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("순서 (패턴)", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("탭하면 삭제. 위에서부터 순서대로 실행돼.", color = TextLo, fontSize = 11.sp)
                if (pattern.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("아래에서 매크로를 탭해 추가해.", color = TextLo, fontSize = 12.sp)
                } else {
                    pattern.forEachIndexed { i, id ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(MeshLavender.copy(alpha = 0.35f)).padding(12.dp)
                                .clickable { pattern.removeAt(i) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}. ${macroName(id)}", color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("✕", color = TextLo, fontSize = 13.sp)
                        }
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("매크로 추가", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (macros.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("저장된 매크로가 없어.", color = TextLo, fontSize = 12.sp)
                } else {
                    macros.forEach { m ->
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { pattern.add(m.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(m.name, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("+ 추가", color = Accent, fontSize = 12.sp)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { LoopyButton("저장", onClick = onSave) }
                Box(Modifier.weight(1f)) { LoopyButton("취소", filled = false, onClick = onCancel) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoopyButton(text: String, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val base = Modifier.fillMaxWidth().height(50.dp).clip(shape).alpha(if (enabled) 1f else 0.45f)
    val styled = if (filled) base.background(Brush.horizontalGradient(listOf(MeshPeach, MeshLavender, MeshMint)))
    else base.background(LoopyCard).border(1.dp, CardStroke, shape)
    val clickMod = if (enabled) styled.clickable { onClick() } else styled
    Box(clickMod, contentAlignment = Alignment.Center) {
        Text(text, color = if (filled) TextHi else Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
LOOPY_EOF

echo "반영."
git add -A
git commit -m "플레이리스트: 패턴+셔플백+무한/N회 재생 + 메인앱 편집기"
git push
echo "푸시 완료!"

