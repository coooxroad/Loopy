package com.loopy.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.media.projection.MediaProjectionManager
import com.loopy.app.overlay.VideoSession
import com.loopy.app.editor.MacroEditorScreen
import com.loopy.app.core.record.Timeline
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.core.material.Material
import com.loopy.app.data.MaterialStore
import com.loopy.app.overlay.OverlayService
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import com.loopy.app.service.LoopyService
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.GradientTitle
import com.loopy.app.ui.theme.LineIcon
import com.loopy.app.ui.theme.SoftCard
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.blocks.BlockCanvas
import com.loopy.app.core.material.ParamBag
import com.loopy.app.core.material.Meta
import com.loopy.app.ui.components.EmptyState
import com.loopy.app.ui.components.NeuFab
import com.loopy.app.ui.components.NeuIconButton
import com.loopy.app.ui.components.GradientText
import com.loopy.app.ui.components.Icon
import com.loopy.app.ui.components.LoopyIcon
import com.loopy.app.ui.components.NeuButton
import com.loopy.app.ui.components.NeuCard
import com.loopy.app.ui.components.NeuOutlineButton
import com.loopy.app.ui.components.NeuListItem
import com.loopy.app.ui.components.NeuToggle
import com.loopy.app.ui.theme.Space
import com.loopy.app.ui.theme.Type
import com.loopy.app.ui.theme.palette
import com.loopy.app.ui.theme.ThemeMode
import com.loopy.app.ui.theme.ThemePref
import com.loopy.app.ui.theme.LoopyTheme
import com.loopy.app.ui.theme.NeuBase
import com.loopy.app.ui.theme.AnimatedBottomGradient
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
        val activity = this
        setContent {
            var themeMode by remember { mutableStateOf(ThemePref.get(activity)) }
            LoopyTheme(mode = themeMode) {
                RootScreen(
                    registerRefresh = { cb -> onShizukuChanged = cb },
                    themeMode = themeMode,
                    onThemeChange = { m ->
                        themeMode = m
                        ThemePref.set(activity, m)
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
    }
}

private enum class Tab(val label: String, val icon: String) {
    DASHBOARD("대시보드", "◈"), LIBRARY("라이브러리", "▤"), SETTINGS("설정", "⚙"),
}

@Composable
private fun RootScreen(
    registerRefresh: ((() -> Unit)) -> Unit,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val mpm = remember { context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    val sessionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            VideoSession.code = result.resultCode
            VideoSession.data = result.data
            context.startForegroundService(
                Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_SET_SESSION }
            )
        }
    }
    fun toggleSession(on: Boolean) {
        if (on) {
            runCatching { sessionLauncher.launch(mpm.createScreenCaptureIntent()) }
        } else {
            context.startService(
                Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_END_SESSION }
            )
            VideoSession.active = false
        }
    }

    var state by remember { mutableStateOf(ShizukuManager.state()) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var overlayMsg by remember { mutableStateOf("오버레이를 켜고 게임으로 전환한 뒤, 컨트롤 바에서 녹화/재생.") }
    var builds by remember { mutableStateOf(MaterialStore.load(context).filter { it.typeId == "build" }) }
    var renaming by remember { mutableStateOf<Material?>(null) }
    var nameField by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }

    // 앱 시작 시 권한 안내 팝업 (Shizuku 우선, 그다음 오버레이)
    var showShizukuDialog by remember { mutableStateOf(state != ShizukuState.READY) }
    var showOverlayDialog by remember { mutableStateOf(false) }

    // 편집기 상태
    var editingBuild by remember { mutableStateOf<Material?>(null) }
    var blocksBuild by remember { mutableStateOf<Material?>(null) }

    fun refresh() {
        builds = MaterialStore.load(context).filter { it.typeId == "build" }
    }


    LaunchedEffect(Unit) {
        registerRefresh {
            state = ShizukuManager.state()
            canOverlay = Settings.canDrawOverlays(context)
            refresh()
        }
    }
    LaunchedEffect(state) {
        if (state == ShizukuState.READY) {
            LoopyService.bind(context)
            if (!canOverlay) showOverlayDialog = true
        }
    }

    if (blocksBuild != null) {
        BlockCanvas(
            build = blocksBuild!!,
            onBack = { blocksBuild = null; refresh() },
            onRun = { m -> OverlayService.runBuild(context, m.id) },
            onOpenTouch = {
                // 터치 블록 → 궤적 편집(타임라인). blocksBuild 를 비워야 화면이 실제로 넘어간다
                // (분기가 blocksBuild 를 먼저 보기 때문). 시간축으로 못 여는 캔버스면 그대로 둔다.
                blocksBuild?.let { b ->
                    if (Timeline.canOpenAsTimeline(b)) { editingBuild = b; blocksBuild = null }
                }
            },
        )
        return
    }

    if (editingBuild != null) {
        MacroEditorScreen(
            build = editingBuild!!,
            onBack = { editingBuild = null; refresh() },
        )
        return
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = NeuBase) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { LineIcon(kind = t.name.lowercase(), color = if (tab == t) Accent else TextLo) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent, selectedTextColor = Accent,
                            unselectedIconColor = TextLo, unselectedTextColor = TextLo,
                            indicatorColor = Accent.copy(alpha = 0.12f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        // 배경과 표면은 같은 색이어야 한다. 다르면 카드가 "올려진 것"이 되어 뉴모피즘이 깨진다.
        Box(Modifier.fillMaxSize().background(palette.surface).padding(padding)) {
            AnimatedBottomGradient()

            when (tab) {
                Tab.DASHBOARD -> DashboardTab(
                    state = state, canOverlay = canOverlay, msg = overlayMsg,
                    recentBuild = builds.firstOrNull(),
                    onToggleOverlay = { turningOn ->
                        if (turningOn) {
                            context.startForegroundService(Intent(context, OverlayService::class.java))
                            overlayMsg = "켜졌어! 게임으로 전환 → 녹화/재생, 📁 목록."
                        } else {
                            context.stopService(Intent(context, OverlayService::class.java))
                            overlayMsg = "오버레이를 껐어."
                        }
                    },
                    sessionActive = VideoSession.active,
                    onToggleSession = { toggleSession(it) },
                )
                Tab.LIBRARY -> LibraryTab(
                    builds = builds,
                    onRefresh = { refresh() },
                    onRename = { renaming = it; nameField = it.meta.name },
                    onDelete = { MaterialStore.delete(context, it.id); refresh() },
                    onEdit = { if (Timeline.canOpenAsTimeline(it)) editingBuild = it else blocksBuild = it },
                    onOpenBlocks = { blocksBuild = it },
                    onNewBuild = {
                        val fresh = Material(
                            id = MaterialStore.newId(),
                            typeId = "build",
                            params = ParamBag.EMPTY,
                            meta = Meta(name = "새 빌드", createdAt = System.currentTimeMillis()),
                        )
                        MaterialStore.upsert(context, fresh)
                        blocksBuild = fresh
                    },
                )
                Tab.SETTINGS -> SettingsTab(
                    themeMode = themeMode,
                    onThemeChange = onThemeChange,
                    state = state, canOverlay = canOverlay,
                    onRequestShizuku = {
                        ShizukuManager.requestPermission { g ->
                            state = if (g) ShizukuState.READY else ShizukuState.NEEDS_PERMISSION
                        }
                    },
                    onRecheckShizuku = { state = ShizukuManager.state() },
                    onOpenOverlaySettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    },
                    onRecheckOverlay = { canOverlay = Settings.canDrawOverlays(context) },
                    sessionActive = VideoSession.active,
                    onToggleSession = { toggleSession(it) },
                )
            }
        }
    }

    // ── 권한 안내 팝업 ──
    if (showShizukuDialog && state != ShizukuState.READY) {
        AlertDialog(
            onDismissRequest = { showShizukuDialog = false },
            confirmButton = {
                TextButton(onClick = { showShizukuDialog = false; tab = Tab.SETTINGS }) {
                    Text("설정으로 이동", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { showShizukuDialog = false }) { Text("나중에", color = TextLo) } },
            title = { Text("Shizuku 연결 필요", color = TextHi) },
            text = { Text("터치 재현에 Shizuku 권한이 필요합니다", color = TextLo, fontSize = 13.sp) },
            containerColor = LoopyCard,
        )
    } else if (showOverlayDialog && !canOverlay) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            confirmButton = {
                TextButton(onClick = { showOverlayDialog = false; tab = Tab.SETTINGS }) {
                    Text("설정으로 이동", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { showOverlayDialog = false }) { Text("나중에", color = TextLo) } },
            title = { Text("오버레이 권한 필요", color = TextHi) },
            text = { Text("화면 위에 컨트롤을 띄우려면 '다른 앱 위에 표시' 권한이 필요합니다", color = TextLo, fontSize = 13.sp) },
            containerColor = LoopyCard,
        )
    }

    val editing = renaming
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                TextButton(onClick = {
                    if (nameField.isNotBlank()) {
                        MaterialStore.upsert(
                            context,
                            editing.copy(meta = editing.meta.copy(name = nameField.trim())),
                        )
                    }
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
