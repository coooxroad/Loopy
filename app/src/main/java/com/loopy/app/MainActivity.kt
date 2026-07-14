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
import com.loopy.app.core.material.BuildParams
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
    var editorOpen by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var plName by remember { mutableStateOf("") }
    var plShuffle by remember { mutableStateOf(false) }
    var plCycles by remember { mutableStateOf("") }
    var plGap by remember { mutableStateOf("") }
    val pattern = remember { mutableStateListOf<String>() }
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
            onOpenTouch = { editingBuild = blocksBuild },
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
                    onEdit = { editingBuild = it },
                    onOpenBlocks = { blocksBuild = it },
                    onNewBuild = {
                        val fresh = Material(
                            id = MaterialStore.newId(),
                            typeId = "build",
                            params = BuildParams(null),
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
    recentBuild: Material?,
    onToggleOverlay: (Boolean) -> Unit,
    sessionActive: Boolean,
    onToggleSession: (Boolean) -> Unit,
) {
    val p = palette
    var overlayOn by remember { mutableStateOf(false) }
    val ready = state == ShizukuState.READY && canOverlay

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.sm),
    ) {
        Spacer(Modifier.height(Space.xl))
        Column(Modifier.padding(horizontal = Space.md)) {
            GradientText("Loopy", fontSize = Type.title)
            Text("터치 자동화", color = p.textMuted, fontSize = Type.caption)
        }
        Spacer(Modifier.height(Space.lg))

        // 오버레이가 이 앱의 관문이다. 켜지 않으면 아무것도 시작할 수 없으므로
        // 꺼져 있을 때는 큰 버튼으로, 켜져 있을 때는 상태로 보여준다.
        NeuCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "오버레이",
                        color = p.textStrong,
                        fontSize = Type.heading,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        if (overlayOn) "화면에 컨트롤이 떠 있습니다" else "켜면 화면 위에서 녹화할 수 있습니다",
                        color = p.textMuted,
                        fontSize = Type.caption,
                    )
                }
                NeuToggle(
                    checked = overlayOn,
                    onCheckedChange = {
                        if (!ready) return@NeuToggle
                        overlayOn = it
                        onToggleOverlay(it)
                    },
                )
            }
            if (!ready) {
                Spacer(Modifier.height(Space.sm))
                // 경고가 아니라 안내다. 빨강은 무언가 잘못됐다는 뜻이라 여기서는 과하다.
                Text(msg, color = p.textMuted, fontSize = Type.label)
            }
        }

        NeuCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "화면 녹화",
                        color = p.textStrong,
                        fontSize = Type.heading,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        if (sessionActive) "매크로에 영상이 함께 기록됩니다" else "터치만 기록합니다",
                        color = p.textMuted,
                        fontSize = Type.caption,
                    )
                }
                NeuToggle(checked = sessionActive, onCheckedChange = onToggleSession)
            }
        }

        if (recentBuild != null) {
            Spacer(Modifier.height(Space.sm))
            Text(
                "최근",
                color = p.textMuted,
                fontSize = Type.label,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = Space.lg, bottom = Space.xs),
            )
            NeuListItem(
                leading = { LoopyIcon(Icon.PLAY, p.accent, size = 18.dp) },
            ) {
                Text(
                    recentBuild.meta.name.ifEmpty { "이름 없음" },
                    color = p.textStrong,
                    fontSize = Type.body,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${recentBuild.children.size}개 블록",
                    color = p.textMuted,
                    fontSize = Type.caption,
                )
            }
        }

        Spacer(Modifier.height(Space.xxl))
    }
}

@Composable
private fun LibraryTab(
    builds: List<Material>,
    onRefresh: () -> Unit,
    onRename: (Material) -> Unit,
    onDelete: (Material) -> Unit,
    onEdit: (Material) -> Unit,
    onOpenBlocks: (Material) -> Unit,
    onNewBuild: () -> Unit,
) {
    val p = palette
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.sm),
        ) {
            Spacer(Modifier.height(Space.xl))
            Row(
                Modifier.padding(horizontal = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GradientText("라이브러리", fontSize = Type.title, modifier = Modifier.weight(1f))
                NeuIconButton(onClick = onRefresh, size = 40.dp) {
                    LoopyIcon(Icon.REDO, p.textMuted, size = 16.dp)
                }
            }
            Spacer(Modifier.height(Space.md))

            if (builds.isEmpty()) {
                Spacer(Modifier.height(Space.xxl))
                EmptyState(
                    title = "아직 비어 있습니다",
                    description = "오버레이에서 녹화하거나,\n블록을 조립해 빌드를 만들어 보세요",
                    action = {
                        NeuButton("새 빌드 만들기", onClick = onNewBuild, modifier = Modifier.width(200.dp))
                    },
                )
            } else {
                builds.forEach { m ->
                    val recorded = m.children.any { it.typeId == "touch" || it.typeId == "parallel" }
                    NeuListItem(
                        onClick = { onOpenBlocks(m) },
                        leading = {
                            LoopyIcon(
                                if (recorded) Icon.RECORD else Icon.LIST,
                                if (recorded) p.accent else p.textMuted,
                                size = 18.dp,
                            )
                        },
                        trailing = {
                            NeuIconButton(onClick = { onEdit(m) }, size = 36.dp) {
                                LoopyIcon(Icon.EDIT, p.textMuted, size = 15.dp)
                            }
                            NeuIconButton(onClick = { onRename(m) }, size = 36.dp) {
                                LoopyIcon(Icon.MORE, p.textMuted, size = 15.dp)
                            }
                            NeuIconButton(onClick = { onDelete(m) }, size = 36.dp) {
                                LoopyIcon(Icon.DELETE, p.danger, size = 15.dp)
                            }
                        },
                    ) {
                        Text(
                            m.meta.name.ifEmpty { "이름 없음" },
                            color = p.textStrong,
                            fontSize = Type.body,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${m.children.size}개 블록",
                            color = p.textMuted,
                            fontSize = Type.caption,
                        )
                    }
                }
            }
            Spacer(Modifier.height(96.dp))
        }

        if (builds.isNotEmpty()) {
            NeuFab(
                onClick = onNewBuild,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Space.lg),
            ) {
                LoopyIcon(Icon.ADD, Color.White, size = 22.dp)
            }
        }
    }
}

@Composable
private fun SettingsTab(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    state: ShizukuState,
    canOverlay: Boolean,
    onRequestShizuku: () -> Unit,
    onRecheckShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRecheckOverlay: () -> Unit,
    sessionActive: Boolean,
    onToggleSession: (Boolean) -> Unit,
) {
    val p = palette
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        GradientText("설정", fontSize = Type.title)

        // 이 앱은 게임 위에서 오래 켜두는 도구라 화면 밝기가 눈에 남는다.
        // 시스템 설정을 뒤지지 않고 여기서 바로 고를 수 있어야 한다.
        NeuCard(Modifier.fillMaxWidth()) {
            Text(
                "테마",
                color = p.textStrong,
                fontSize = Type.heading,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                for (m in ThemeMode.entries) {
                    val label = when (m) {
                        ThemeMode.SYSTEM -> "시스템"
                        ThemeMode.LIGHT -> "밝게"
                        ThemeMode.DARK -> "어둡게"
                    }
                    Box(Modifier.weight(1f)) {
                        if (m == themeMode) {
                            NeuButton(label, onClick = { onThemeChange(m) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            NeuOutlineButton(label, onClick = { onThemeChange(m) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

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
