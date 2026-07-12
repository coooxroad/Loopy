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
        setContent { LoopyTheme { RootScreen(registerRefresh = { cb -> onShizukuChanged = cb }) } }
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
private fun RootScreen(registerRefresh: ((() -> Unit)) -> Unit) {
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
    var macros by remember { mutableStateOf(MacroStore.list(context)) }
    var renaming by remember { mutableStateOf<Macro?>(null) }
    var nameField by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(Tab.DASHBOARD) }

    // 앱 시작 시 권한 안내 팝업 (Shizuku 우선, 그다음 오버레이)
    var showShizukuDialog by remember { mutableStateOf(state != ShizukuState.READY) }
    var showOverlayDialog by remember { mutableStateOf(false) }

    // 편집기 상태
    var editorOpen by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var plName by remember { mutableStateOf("") }
    var plShuffle by remember { mutableStateOf(false) }
    var plCycles by remember { mutableStateOf("") }
    var plGap by remember { mutableStateOf("") }
    val pattern = remember { mutableStateListOf<String>() }
    var editorMacros by remember { mutableStateOf<List<Macro>>(emptyList()) }
    var editingMacro by remember { mutableStateOf<Macro?>(null) }

    fun refresh() {
        macros = MacroStore.list(context)
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

    if (editingMacro != null) {
        MacroEditorScreen(
            macro = editingMacro!!,
            onBack = { editingMacro = null; refresh() },
        )
        return
    }

    if (editorOpen) {
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
        Box(Modifier.fillMaxSize().background(NeuBase).padding(padding)) {
            AnimatedBottomGradient()

            when (tab) {
                Tab.DASHBOARD -> DashboardTab(
                    state = state, canOverlay = canOverlay, msg = overlayMsg,
                    recentMacro = macros.firstOrNull(),
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
                    macros = macros,
                    onRefresh = { refresh() },
                    onRename = { renaming = it; nameField = it.name },
                    onDelete = { MacroStore.delete(context, it.id); refresh() },
                    onEdit = { editingMacro = it },
                )
                Tab.SETTINGS -> SettingsTab(
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
private fun ScreenColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun DashboardTab(
    state: ShizukuState,
    canOverlay: Boolean,
    msg: String,
    recentMacro: Macro?,
    onToggleOverlay: (Boolean) -> Unit,
    sessionActive: Boolean,
    onToggleSession: (Boolean) -> Unit,
) {
    var overlayOn by remember { mutableStateOf(false) }
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        GradientTitle("Loopy", size = 34)
        Text("레코드 매크로", color = TextLo, fontSize = 14.sp)

        SoftCard(Modifier.fillMaxWidth()) {
            Text("오버레이", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(msg, color = TextLo, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            val ready = state == ShizukuState.READY && canOverlay
            LoopyButton(
                text = if (overlayOn) "오버레이 끄기" else "오버레이 켜기",
                filled = !overlayOn,
                enabled = ready,
            ) {
                overlayOn = !overlayOn
                onToggleOverlay(overlayOn)
            }
            if (!ready) {
                Spacer(Modifier.height(8.dp))
                Text("설정에서 권한을 허용하세요", color = TextLo, fontSize = 11.sp)
            }
        }

        VideoSessionCard(sessionActive, onToggleSession)

        SoftCard(Modifier.fillMaxWidth()) {
            Text("최근 사용", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (recentMacro == null) {
                Text("녹화한 매크로가 여기 표시됩니다", color = TextLo, fontSize = 12.sp)
            } else {
                recentMacro.let {
                    Text("📄 ${it.name}", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("${it.strokes.size} 스트로크", color = TextLo, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))

            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LibraryTab(
    macros: List<Macro>,
    onRefresh: () -> Unit,
    onRename: (Macro) -> Unit,
    onDelete: (Macro) -> Unit,
    onEdit: (Macro) -> Unit,
) {
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientTitle("라이브러리", size = 28, modifier = Modifier.weight(1f))
            Text("새로고침", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { onRefresh() })
        }
        if (macros.isEmpty()) {
            SoftCard(Modifier.fillMaxWidth()) {
                Text("녹화한 매크로가 여기 표시됩니다", color = TextLo, fontSize = 13.sp)
            }
        } else {
            macros.forEach { m ->
                SoftCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("${m.strokes.size} 스트로크", color = TextLo, fontSize = 12.sp)
                        }
                        Text("편집", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { onEdit(m) })
                        Spacer(Modifier.width(14.dp))
                        Text("이름변경", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { onRename(m) })
                        Spacer(Modifier.width(14.dp))
                        Text("삭제", color = TextLo, fontSize = 13.sp, modifier = Modifier.clickable { onDelete(m) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsTab(
    state: ShizukuState,
    canOverlay: Boolean,
    onRequestShizuku: () -> Unit,
    onRecheckShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRecheckOverlay: () -> Unit,
    sessionActive: Boolean,
    onToggleSession: (Boolean) -> Unit,
) {
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        GradientTitle("설정", size = 28)

        SoftCard(Modifier.fillMaxWidth()) {
            Text("Shizuku", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                LoopyButton("권한 허용", onClick = onRequestShizuku)
            } else if (state == ShizukuState.NOT_INSTALLED) {
                Spacer(Modifier.height(12.dp))
                LoopyButton("다시 확인", onClick = onRecheckShizuku)
            }
        }

        SoftCard(Modifier.fillMaxWidth()) {
            Text("오버레이 권한", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (canOverlay) "허용됨" else "다른 앱 위에 표시 권한 필요",
                color = if (canOverlay) Accent else TextLo, fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (!canOverlay) {
                LoopyButton("권한 설정 열기", onClick = onOpenOverlaySettings)
                Spacer(Modifier.height(8.dp))
            }
            LoopyButton("권한 상태 새로고침", filled = false, onClick = onRecheckOverlay)
        }
        VideoSessionCard(sessionActive, onToggleSession)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VideoSessionCard(active: Boolean, onToggle: (Boolean) -> Unit) {
    SoftCard(Modifier.fillMaxWidth()) {
        Text("화면 녹화 세션", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (active) "세션 켜짐 · 오버레이 영상 버튼(초록)을 켜고 녹화하면 팝업 없이 화면도 저장돼."
            else "켜면 화면 녹화 권한을 한 번만 받아둬. 이후 오버레이 녹화 시 팝업·앱전환 없이 영상이 저장돼.",
            color = TextLo, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        LoopyButton(text = if (active) "세션 끄기" else "세션 켜기", filled = !active) { onToggle(!active) }
        if (active) {
            Spacer(Modifier.height(8.dp))
            Text("녹화 중에는 상태바에 캐스트 아이콘이 표시됩니다", color = TextLo, fontSize = 11.sp)
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
