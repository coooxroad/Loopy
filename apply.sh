#!/data/data/com.termux/files/usr/bin/bash
# Loopy UI: Spotify식 하단 탭바(홈/플레이리스트/라이브러리/설정) + 권한 팝업
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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

private enum class Tab(val label: String, val icon: String) {
    HOME("홈", "🏠"), PLAYLIST("플레이리스트", "🎵"), LIBRARY("라이브러리", "📁"), SETTINGS("설정", "⚙️"),
}

@Composable
private fun RootScreen(registerRefresh: ((() -> Unit)) -> Unit) {
    val context = LocalContext.current

    var state by remember { mutableStateOf(ShizukuManager.state()) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var overlayMsg by remember { mutableStateOf("오버레이를 켜고 게임으로 전환한 뒤, 컨트롤 바에서 녹화/재생.") }
    var macros by remember { mutableStateOf(MacroStore.list(context)) }
    var playlists by remember { mutableStateOf(PlaylistStore.list(context)) }
    var renaming by remember { mutableStateOf<Macro?>(null) }
    var nameField by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(Tab.HOME) }

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
        plGap = pl?.gapMs?.takeIf { it > 0 }?.let { (it / 1000.0).toString() } ?: ""
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
    LaunchedEffect(state) {
        if (state == ShizukuState.READY) {
            LoopyService.bind(context)
            if (!canOverlay) showOverlayDialog = true
        }
    }

    if (editorOpen) {
        PlaylistEditor(
            name = plName, onName = { plName = it },
            shuffle = plShuffle, onShuffle = { plShuffle = it },
            cycles = plCycles, onCycles = { plCycles = it.filter { c -> c.isDigit() } },
            gap = plGap, onGap = { plGap = it.filter { c -> c.isDigit() || c == '.' } },
            pattern = pattern,
            macros = editorMacros,
            onSave = {
                if (plName.isNotBlank() && pattern.isNotEmpty()) {
                    PlaylistStore.save(
                        context, plName.trim(), pattern.toList(),
                        plShuffle, plCycles.toIntOrNull() ?: 0,
                        ((plGap.toDoubleOrNull() ?: 0.0) * 1000).toInt(), editId,
                    )
                    refresh()
                    editorOpen = false
                }
            },
            onCancel = { editorOpen = false },
        )
        return
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = LoopyCard) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.icon, fontSize = 18.sp) },
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            MeshGradientBackground(animate = true)

            when (tab) {
                Tab.HOME -> HomeTab(
                    state = state, canOverlay = canOverlay, msg = overlayMsg,
                    recentMacro = macros.firstOrNull(), recentPlaylist = playlists.firstOrNull(),
                    onToggleOverlay = { turningOn ->
                        if (turningOn) {
                            context.startForegroundService(Intent(context, OverlayService::class.java))
                            overlayMsg = "켜졌어! 게임으로 전환 → 녹화/재생, 📁 목록."
                        } else {
                            context.stopService(Intent(context, OverlayService::class.java))
                            overlayMsg = "오버레이를 껐어."
                        }
                    },
                )
                Tab.PLAYLIST -> PlaylistTab(
                    playlists = playlists,
                    onNew = { openEditor(null) },
                    onEdit = { openEditor(it) },
                    onDelete = { PlaylistStore.delete(context, it.id); refresh() },
                )
                Tab.LIBRARY -> LibraryTab(
                    macros = macros,
                    onRefresh = { refresh() },
                    onRename = { renaming = it; nameField = it.name },
                    onDelete = { MacroStore.delete(context, it.id); refresh() },
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
            text = { Text("Loopy가 터치를 읽고 재현하려면 Shizuku 권한이 필요해. 설정 탭에서 허용해줘.", color = TextLo, fontSize = 13.sp) },
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
            text = { Text("게임 위에 컨트롤 바를 띄우려면 '다른 앱 위에 표시' 권한이 필요해.", color = TextLo, fontSize = 13.sp) },
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
private fun HomeTab(
    state: ShizukuState,
    canOverlay: Boolean,
    msg: String,
    recentMacro: Macro?,
    recentPlaylist: Playlist?,
    onToggleOverlay: (Boolean) -> Unit,
) {
    var overlayOn by remember { mutableStateOf(false) }
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        Text("Loopy", color = TextHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("레코드 매크로", color = TextLo, fontSize = 14.sp)

        GlassCard(Modifier.fillMaxWidth()) {
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
                Text("설정 탭에서 Shizuku·오버레이 권한을 먼저 허용해줘.", color = TextLo, fontSize = 11.sp)
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text("최근 사용", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (recentPlaylist == null && recentMacro == null) {
                Text("아직 없어. 오버레이에서 녹화해봐.", color = TextLo, fontSize = 12.sp)
            } else {
                recentPlaylist?.let {
                    Text("🎵 ${it.name}", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("${it.macroIds.size}스텝 플레이리스트", color = TextLo, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                }
                recentMacro?.let {
                    Text("📄 ${it.name}", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("${it.strokes.size} 스트로크", color = TextLo, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("편집은 라이브러리·플레이리스트 탭에서 (곧 지원 예정).", color = TextLo, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PlaylistTab(
    playlists: List<Playlist>,
    onNew: () -> Unit,
    onEdit: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("플레이리스트", color = TextHi, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("+ 새로 만들기", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { onNew() })
        }
        if (playlists.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("매크로를 2개 이상 저장한 뒤 플레이리스트를 만들어봐.", color = TextLo, fontSize = 13.sp)
            }
        } else {
            playlists.forEach { pl ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val rep = if (pl.cycles == 0) "무한" else "${pl.cycles}회"
                            val sh = if (pl.shuffle) " · 셔플" else ""
                            val gp = if (pl.gapMs > 0) " · 대기 ${pl.gapMs / 1000.0}s" else ""
                            Text("${pl.macroIds.size}스텝 · $rep$sh$gp", color = TextLo, fontSize = 12.sp)
                        }
                        Text("편집", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { onEdit(pl) })
                        Spacer(Modifier.width(14.dp))
                        Text("삭제", color = TextLo, fontSize = 13.sp, modifier = Modifier.clickable { onDelete(pl) })
                    }
                }
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
) {
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("라이브러리", color = TextHi, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("새로고침", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { onRefresh() })
        }
        if (macros.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("아직 없어. 오버레이에서 녹화하면 여기 쌓여.", color = TextLo, fontSize = 13.sp)
            }
        } else {
            macros.forEach { m ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("${m.strokes.size} 스트로크", color = TextLo, fontSize = 12.sp)
                        }
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
) {
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        Text("설정", color = TextHi, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        GlassCard(Modifier.fillMaxWidth()) {
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

        GlassCard(Modifier.fillMaxWidth()) {
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
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PlaylistEditor(
    name: String, onName: (String) -> Unit,
    shuffle: Boolean, onShuffle: (Boolean) -> Unit,
    cycles: String, onCycles: (String) -> Unit,
    gap: String, onGap: (String) -> Unit,
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
                Spacer(Modifier.height(10.dp))
                Text("매크로 사이 대기 (초, 비우면 0)", color = TextLo, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = gap, onValueChange = onGap, singleLine = true,
                    placeholder = { Text("0") }, modifier = Modifier.width(140.dp))
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
git commit -m "UI: Spotify식 하단 탭바(홈/플레이리스트/라이브러리/설정) + 권한 팝업"
git push
echo "푸시 완료!"

