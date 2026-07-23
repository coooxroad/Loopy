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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.loopy.app.overlay.OverlayService
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import com.loopy.app.service.LoopyService
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.LineIcon
import com.loopy.app.ui.theme.LoopyCard
import com.loopy.app.blocks.BlockCanvas
import com.loopy.app.ui.theme.palette
import com.loopy.app.ui.theme.ThemeMode
import com.loopy.app.ui.theme.ThemePref
import com.loopy.app.ui.theme.LoopyTheme
import com.loopy.app.ui.theme.NeuBase
import com.loopy.app.ui.theme.AnimatedBottomGradient
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

enum class Tab(val label: String, val icon: String) {
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

    // 화면 상태는 홀더 한 곳에 모여 있다. 여기서는 그리기와 이벤트 전달만 한다.
    val app = remember {
        AppState(
            shizuku = ShizukuManager.state(),
            canOverlay = Settings.canDrawOverlays(context),
            builds = AppState.loadBuilds(context),
        )
    }

    LaunchedEffect(Unit) {
        registerRefresh { app.recheckAll(context) }
    }
    LaunchedEffect(app.shizuku) {
        if (app.shizuku == ShizukuState.READY) {
            LoopyService.bind(context)
            if (!app.canOverlay) app.askOverlayPermission()
        }
    }

    if (app.blocksBuild != null) {
        BlockCanvas(
            build = app.blocksBuild!!,
            onBack = { app.closeEditors(context) },
            onRun = { m -> OverlayService.runBuild(context, m.id) },
            onOpenTouch = { app.openTouchTimeline() },
        )
        return
    }

    if (app.editingBuild != null) {
        MacroEditorScreen(
            build = app.editingBuild!!,
            onBack = { app.closeEditors(context) },
        )
        return
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = NeuBase) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = app.tab == t,
                        onClick = { app.selectTab(t) },
                        icon = { LineIcon(kind = t.name.lowercase(), color = if (app.tab == t) Accent else TextLo) },
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

            when (app.tab) {
                Tab.DASHBOARD -> DashboardTab(
                    state = app.shizuku, canOverlay = app.canOverlay, msg = app.overlayMsg,
                    recentBuild = app.builds.firstOrNull(),
                    onToggleOverlay = { turningOn ->
                        if (turningOn) {
                            context.startForegroundService(Intent(context, OverlayService::class.java))
                            app.updateOverlayMsg("켜졌어! 게임으로 전환 → 녹화/재생, 📁 목록.")
                        } else {
                            context.stopService(Intent(context, OverlayService::class.java))
                            app.updateOverlayMsg("오버레이를 껐어.")
                        }
                    },
                    sessionActive = VideoSession.active,
                    onToggleSession = { toggleSession(it) },
                )
                Tab.LIBRARY -> LibraryTab(
                    builds = app.builds,
                    onRefresh = { app.refresh(context) },
                    onRename = { app.startRename(it) },
                    onDelete = { app.delete(context, it) },
                    onEdit = { app.edit(it) },
                    onOpenBlocks = { app.openBlocks(it) },
                    onNewBuild = { app.newBuild(context) },
                )
                Tab.SETTINGS -> SettingsTab(
                    themeMode = themeMode,
                    onThemeChange = onThemeChange,
                    state = app.shizuku, canOverlay = app.canOverlay,
                    onRequestShizuku = {
                        ShizukuManager.requestPermission { g ->
                            app.updateShizuku(if (g) ShizukuState.READY else ShizukuState.NEEDS_PERMISSION)
                        }
                    },
                    onRecheckShizuku = { app.recheckShizuku() },
                    onOpenOverlaySettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    },
                    onRecheckOverlay = { app.recheckOverlay(context) },
                    sessionActive = VideoSession.active,
                    onToggleSession = { toggleSession(it) },
                )
            }
        }
    }

    // ── 권한 안내 팝업 ──
    if (app.showShizukuDialog && app.shizuku != ShizukuState.READY) {
        AlertDialog(
            onDismissRequest = { app.dismissDialogs() },
            confirmButton = {
                TextButton(onClick = { app.goToSettings() }) {
                    Text("설정으로 이동", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { app.dismissDialogs() }) { Text("나중에", color = TextLo) } },
            title = { Text("Shizuku 연결 필요", color = TextHi) },
            text = { Text("터치 재현에 Shizuku 권한이 필요합니다", color = TextLo, fontSize = 13.sp) },
            containerColor = LoopyCard,
        )
    } else if (app.showOverlayDialog && !app.canOverlay) {
        AlertDialog(
            onDismissRequest = { app.dismissDialogs() },
            confirmButton = {
                TextButton(onClick = { app.goToSettings() }) {
                    Text("설정으로 이동", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { app.dismissDialogs() }) { Text("나중에", color = TextLo) } },
            title = { Text("오버레이 권한 필요", color = TextHi) },
            text = { Text("화면 위에 컨트롤을 띄우려면 '다른 앱 위에 표시' 권한이 필요합니다", color = TextLo, fontSize = 13.sp) },
            containerColor = LoopyCard,
        )
    }

    if (app.renaming != null) {
        AlertDialog(
            onDismissRequest = { app.cancelRename() },
            confirmButton = {
                TextButton(onClick = { app.commitRename(context) }) { Text("저장", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { app.cancelRename() }) { Text("취소", color = TextLo) } },
            title = { Text("이름 변경", color = TextHi) },
            text = {
                OutlinedTextField(
                    value = app.nameField,
                    onValueChange = { app.editName(it) },
                    singleLine = true,
                )
            },
            containerColor = LoopyCard,
        )
    }
}
