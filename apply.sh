#!/data/data/com.termux/files/usr/bin/bash
# Loopy: Deep SLock(검은 레이어+최소밝기, 달 버튼 토글) + 녹화 아이콘 수정
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

mkdir -p "$(dirname "app/src/main/aidl/com/loopy/app/service/ILoopyService.aidl")"
cat > "app/src/main/aidl/com/loopy/app/service/ILoopyService.aidl" << 'LOOPY_EOF'
// Shizuku UserService 인터페이스. elevated(shell) 프로세스에서 실행되는 메서드들.
package com.loopy.app.service;

interface ILoopyService {
    void destroy() = 16777114;
    void exit() = 1;

    // 여러 스트로크를 시간축에 병합해 동시 재생(멀티터치). 단일 손가락도 포함.
    // 각 스트로크의 샘플을 평탄 배열로 이어붙이고 sampleCounts 로 경계를 나눈다.
    //  fingerIds[s]    = 스트로크 s 의 손가락 id
    //  startMs[s]      = 스트로크 s 의 절대 시작 시각(전체 기준 ms)
    //  durationsMs[s]  = 스트로크 s 의 down→up 총 지속시간(홀드 유지 재현)
    //  sampleCounts[s] = 스트로크 s 의 샘플 수
    //  xsFlat/ysFlat   = 모든 샘플의 픽셀 좌표(순서대로)
    //  timesFlat       = 각 샘플의 "스트로크 시작 기준" ms
    void playMulti(in int[] fingerIds, in long[] startMs, in long[] durationsMs, in int[] sampleCounts,
                   in int[] xsFlat, in int[] ysFlat, in long[] timesFlat) = 2;
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
                Text("설정 탭에서 Shizuku·오버레이 권한을 먼저 허용해줘.", color = TextLo, fontSize = 11.sp)
            }
        }

        SoftCard(Modifier.fillMaxWidth()) {
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
            GradientTitle("플레이리스트", size = 28, modifier = Modifier.weight(1f))
            Text("+ 새로 만들기", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { onNew() })
        }
        if (playlists.isEmpty()) {
            SoftCard(Modifier.fillMaxWidth()) {
                Text("매크로를 2개 이상 저장한 뒤 플레이리스트를 만들어봐.", color = TextLo, fontSize = 13.sp)
            }
        } else {
            playlists.forEach { pl ->
                SoftCard(Modifier.fillMaxWidth()) {
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
            GradientTitle("라이브러리", size = 28, modifier = Modifier.weight(1f))
            Text("새로고침", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { onRefresh() })
        }
        if (macros.isEmpty()) {
            SoftCard(Modifier.fillMaxWidth()) {
                Text("아직 없어. 오버레이에서 녹화하면 여기 쌓여.", color = TextLo, fontSize = 13.sp)
            }
        } else {
            macros.forEach { m ->
                SoftCard(Modifier.fillMaxWidth()) {
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
    Box(Modifier.fillMaxSize().background(NeuBase)) {
        AnimatedBottomGradient()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            GradientTitle("플레이리스트 편집", size = 26)

            SoftCard(Modifier.fillMaxWidth()) {
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

            SoftCard(Modifier.fillMaxWidth()) {
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

            SoftCard(Modifier.fillMaxWidth()) {
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

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/GeteventReader.kt")"
cat > "app/src/main/java/com/loopy/app/input/GeteventReader.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 정규화된 터치 한 점 (0.0~1.0). slot = 손가락 번호(멀티터치 구분). */
data class TouchPoint(
    val slot: Int,
    val nx: Float,
    val ny: Float,
    val rawX: Int,
    val rawY: Int,
    val down: Boolean,
)

/** getevent -pl 로 찾아낸 터치 가능 디바이스. */
data class TouchDevice(
    val path: String,
    val name: String,
    val maxX: Int,
    val maxY: Int,
)

/**
 * getevent 로 물리 터치를 실시간 파싱한다. 멀티터치 프로토콜 B(슬롯) 처리.
 *
 * 정지 신뢰성: readLine() 은 블로킹이라 코루틴 cancel 만으론 안 멈춘다. 그래서
 *  1) 프로세스를 destroy 해 읽기를 끊고,
 *  2) "세대(gen)" 토큰으로 옛 스트림의 방출을 원천 차단한다.
 * stop() 이 gen 을 올리면, 그 이전에 시작된 스트림은 gen 불일치로 다시는 방출하지 못한다
 * (프로세스가 좀비로 남아도 무해). 새 녹화가 옛 스트림을 되살리는 일이 없다.
 */
class GeteventReader {

    private val jobs = mutableListOf<Job>()
    private val procs = mutableListOf<Process>()
    @Volatile private var gen = 0

    fun probe(): List<TouchDevice> {
        val out = Shell.run("getevent -pl") ?: return emptyList()
        var curPath: String? = null
        var curName = ""
        var maxX = -1
        var maxY = -1
        val found = mutableListOf<TouchDevice>()

        fun flush() {
            val p = curPath
            if (p != null && maxX > 0 && maxY > 0) found += TouchDevice(p, curName, maxX, maxY)
        }

        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("add device") -> {
                    flush()
                    curPath = line.substringAfter(": ", "").trim().ifEmpty { null }
                    curName = ""; maxX = -1; maxY = -1
                }
                line.startsWith("name:") -> curName = line.substringAfter("name:").trim().trim('"')
                line.contains("ABS_MT_POSITION_X") -> maxX = extractMax(line).coerceAtLeast(maxX)
                line.contains("ABS_MT_POSITION_Y") -> maxY = extractMax(line).coerceAtLeast(maxY)
            }
        }
        flush()
        return found.sortedByDescending { it.name.contains("touchscreen", ignoreCase = true) }
    }

    private fun extractMax(line: String): Int {
        val i = line.indexOf("max")
        if (i < 0) return -1
        val rest = line.substring(i + 3)
        val num = rest.dropWhile { !it.isDigit() && it != '-' }.takeWhile { it.isDigit() || it == '-' }
        return num.toIntOrNull() ?: -1
    }

    fun stream(
        scope: CoroutineScope,
        devices: List<TouchDevice>,
        onPoint: (TouchDevice, TouchPoint) -> Unit,
    ) {
        stop()
        val myGen = gen
        for (dev in devices) jobs += scope.launch(Dispatchers.IO) { streamOne(myGen, dev, onPoint) }
    }

    private class Slot(var x: Int = -1, var y: Int = -1, var down: Boolean = false)

    private fun streamOne(myGen: Int, dev: TouchDevice, onPoint: (TouchDevice, TouchPoint) -> Unit) {
        val proc = try {
            Shell.newProcess(arrayOf("sh", "-c", "getevent -lt ${dev.path}"))
        } catch (t: Throwable) {
            return
        }
        synchronized(procs) { procs.add(proc) }
        val slots = HashMap<Int, Slot>()
        val touched = HashSet<Int>()
        var curSlot = 0
        try {
            val br = proc.inputStream.bufferedReader()
            while (myGen == gen) {
                val line = br.readLine() ?: break
                val ev = parseLine(line) ?: continue
                when (ev.code) {
                    "ABS_MT_SLOT" -> { curSlot = ev.value; touched.add(curSlot) }
                    "ABS_MT_TRACKING_ID" -> {
                        slots.getOrPut(curSlot) { Slot() }.down =
                            ev.value != 0xffffffff.toInt() && ev.value != -1
                        touched.add(curSlot)
                    }
                    "ABS_MT_POSITION_X" -> { slots.getOrPut(curSlot) { Slot() }.x = ev.value; touched.add(curSlot) }
                    "ABS_MT_POSITION_Y" -> { slots.getOrPut(curSlot) { Slot() }.y = ev.value; touched.add(curSlot) }
                    "SYN_REPORT" -> {
                        for (sl in touched) {
                            val s = slots[sl] ?: continue
                            if (myGen == gen && s.x in 0..dev.maxX && s.y in 0..dev.maxY) {
                                onPoint(
                                    dev,
                                    TouchPoint(sl, s.x.toFloat() / dev.maxX, s.y.toFloat() / dev.maxY, s.x, s.y, s.down),
                                )
                            }
                        }
                        touched.clear()
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            synchronized(procs) { procs.remove(proc) }
            runCatching { proc.destroy() }
        }
    }

    fun stop() {
        gen++ // 이 시점 이전에 시작된 모든 스트림의 방출을 무효화
        synchronized(procs) {
            procs.forEach { runCatching { it.destroy() } }
            procs.clear()
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private data class Ev(val type: String, val code: String, val value: Int)

    private fun parseLine(line: String): Ev? {
        val body = if (line.startsWith("[")) line.substringAfter("]").trim() else line.trim()
        val toks = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size < 3) return null
        if (!toks[0].startsWith("EV_")) return null
        val value = toks[2].toLongOrNull(16)?.toInt() ?: return null
        return Ev(toks[0], toks[1], value)
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/input/RawRecorder.kt")"
cat > "app/src/main/java/com/loopy/app/input/RawRecorder.kt" << 'LOOPY_EOF'
package com.loopy.app.input

import android.os.SystemClock
import com.loopy.app.macro.Stroke
import com.loopy.app.macro.TouchSample
import java.util.Collections

/**
 * getevent 포인트를 슬롯(손가락)별로 보고 "좌표 타임라인 스트로크"를 통째로 기록한다.
 * 제스처 분류 없음 — 손가락이 그린 모든 미세 이동을 그대로 담으므로 탭/홀드/스와이프/
 * 조이스틱이 전부 자연히 재현된다.
 *
 * (A1=단일 손가락 재현) 여러 손가락이 겹치면 각 슬롯의 스트로크를 따로 기록하고
 * 재생은 시작 시각 순으로 순차 실행한다. 동시 멀티터치는 다음 단계.
 */
class RawRecorder {

    /** panel 좌표(u,v)가 무시 대상(컨트롤 바 위)인지. */
    var shouldIgnore: (Float, Float) -> Boolean = { _, _ -> false }

    private class Builder(val startT: Long, val downX: Float, val downY: Float) {
        val samples = ArrayList<TouchSample>()
    }

    private data class Done(val startT: Long, val endT: Long, val downX: Float, val downY: Float, val samples: List<TouchSample>)

    private val tracks = HashMap<Int, Builder>()
    private val done = Collections.synchronizedList(mutableListOf<Done>())

    fun reset() {
        tracks.clear()
        done.clear()
    }

    fun count(): Int = done.size

    fun onPoint(p: TouchPoint) {
        val now = SystemClock.uptimeMillis()
        val b = tracks[p.slot]
        when {
            p.down && b == null -> {
                val nb = Builder(now, p.nx, p.ny)
                nb.samples.add(TouchSample(0L, p.nx, p.ny))
                tracks[p.slot] = nb
            }
            p.down && b != null -> {
                b.samples.add(TouchSample(now - b.startT, p.nx, p.ny))
            }
            !p.down && b != null -> {
                tracks.remove(p.slot)
                if (shouldIgnore(b.downX, b.downY)) return
                if (b.samples.isNotEmpty()) {
                    done.add(Done(b.startT, now, b.downX, b.downY, b.samples.toList()))
                }
            }
        }
    }

    /** 시작 시각 순 정렬 + 스트로크 사이 대기 계산. */
    fun snapshot(): List<Stroke> {
        val sorted = synchronized(done) { done.toList() }.sortedBy { it.startT }
        if (sorted.isEmpty()) return emptyList()
        val base = sorted.first().startT // 제일 이른 스트로크를 0 기준으로
        val out = ArrayList<Stroke>(sorted.size)
        for (d in sorted) {
            out.add(
                Stroke(
                    startMs = (d.startT - base).coerceAtLeast(0L),
                    durationMs = (d.endT - d.startT).coerceAtLeast(0L),
                    samples = d.samples,
                )
            )
        }
        return out
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/macro/Macro.kt")"
cat > "app/src/main/java/com/loopy/app/macro/Macro.kt" << 'LOOPY_EOF'
package com.loopy.app.macro

/** 저장되는 매크로. strokes = 원본 좌표 타임라인들(탭/홀드/스와이프/조이스틱 통합). */
data class Macro(
    val id: String,
    val name: String,
    val createdAt: Long,
    val strokes: List<Stroke>,
)
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/macro/MacroStore.kt")"
cat > "app/src/main/java/com/loopy/app/macro/MacroStore.kt" << 'LOOPY_EOF'
package com.loopy.app.macro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 매크로를 filesDir/macros/{id}.json 로 저장/관리. org.json 사용(의존성 없음). */
object MacroStore {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "macros").apply { mkdirs() }

    fun autoName(time: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MMM d a h:mm", Locale.ENGLISH).format(Date(time))

    fun saveNew(ctx: Context, strokes: List<Stroke>): Macro {
        val now = System.currentTimeMillis()
        val macro = Macro(UUID.randomUUID().toString(), autoName(now), now, strokes)
        write(ctx, macro)
        return macro
    }

    fun rename(ctx: Context, id: String, newName: String) {
        val m = read(ctx, id) ?: return
        write(ctx, m.copy(name = newName))
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    fun list(ctx: Context): List<Macro> =
        (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun read(ctx: Context, id: String): Macro? =
        runCatching { fromJson(File(dir(ctx), "$id.json").readText()) }.getOrNull()

    private fun write(ctx: Context, macro: Macro) {
        File(dir(ctx), "${macro.id}.json").writeText(toJson(macro))
    }

    private fun toJson(m: Macro): String {
        val strokes = JSONArray()
        for (s in m.strokes) {
            val samples = JSONArray()
            for (p in s.samples) {
                samples.put(
                    JSONObject().put("t", p.t).put("x", p.nx.toDouble()).put("y", p.ny.toDouble())
                )
            }
            strokes.put(JSONObject().put("startMs", s.startMs).put("durationMs", s.durationMs).put("samples", samples))
        }
        return JSONObject()
            .put("id", m.id).put("name", m.name).put("createdAt", m.createdAt)
            .put("strokes", strokes)
            .toString()
    }

    private fun fromJson(text: String): Macro {
        val o = JSONObject(text)
        val strokesArr = o.getJSONArray("strokes")
        val strokes = ArrayList<Stroke>(strokesArr.length())
        for (i in 0 until strokesArr.length()) {
            val so = strokesArr.getJSONObject(i)
            val sampArr = so.getJSONArray("samples")
            val samples = ArrayList<TouchSample>(sampArr.length())
            for (j in 0 until sampArr.length()) {
                val p = sampArr.getJSONObject(j)
                samples.add(TouchSample(p.getLong("t"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat()))
            }
            strokes.add(Stroke(so.optLong("startMs", 0L), so.optLong("durationMs", 0L), samples))
        }
        return Macro(o.getString("id"), o.getString("name"), o.getLong("createdAt"), strokes)
    }
}
LOOPY_EOF

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
    val gapMs: Int, // 매크로 사이 대기(ms)
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
        gapMs: Int,
        existingId: String? = null,
    ): Playlist {
        val pl = Playlist(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            macroIds = macroIds,
            shuffle = shuffle,
            cycles = cycles,
            gapMs = gapMs,
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
            .put("gapMs", p.gapMs)
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
            gapMs = o.optInt("gapMs", 0),
        )
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/macro/Stroke.kt")"
cat > "app/src/main/java/com/loopy/app/macro/Stroke.kt" << 'LOOPY_EOF'
package com.loopy.app.macro

/** 한 시점의 터치 좌표. t = 스트로크 시작 기준 ms, nx/ny = panel 정규화(0~1). */
data class TouchSample(val t: Long, val nx: Float, val ny: Float)

/**
 * 스트로크 = 손가락 하나가 내려와서(첫 샘플) 떼질 때까지의 좌표 타임라인.
 * 탭/홀드/스와이프/조이스틱이 전부 이 하나로 표현된다.
 *
 * startMs = 매크로 전체 기준 절대 시작 시각(ms). 멀티터치 동시 재생의 핵심 —
 *           여러 스트로크의 startMs 가 겹치면 그만큼 동시에 재생된다.
 * durationMs = down→up 총 지속시간(홀드 유지시간 재현용).
 */
data class Stroke(val startMs: Long, val durationMs: Long, val samples: List<TouchSample>)
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/overlay/FabLogoView.kt")"
cat > "app/src/main/java/com/loopy/app/overlay/FabLogoView.kt" << 'LOOPY_EOF'
package com.loopy.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 접힌 상태의 오버레이 FAB. 페리윙클→민트 그라데이션 원 + 흰색 순환(loop) 글리프.
 * 순수 Canvas 로 그려서 별도 리소스 없이 선명하다.
 */
class FabLogoView(context: Context) : View(context) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        circlePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            0xFF6C7BFF.toInt(), 0xFF5FD0E8.toInt(), Shader.TileMode.CLAMP,
        )
        loopPaint.strokeWidth = w * 0.07f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, w / 2f, circlePaint)

        val r = w * 0.24f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, -35f, 295f, false, loopPaint)

        // 화살촉 (호 시작점 근처)
        arrow.reset()
        arrow.moveTo(cx + r * 0.5f, cy - r * 1.15f)
        arrow.lineTo(cx + r * 1.15f, cy - r * 0.55f)
        arrow.lineTo(cx + r * 0.35f, cy - r * 0.35f)
        canvas.drawPath(arrow, loopPaint)
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.loopy.app.R
import com.loopy.app.input.RawRecorder
import com.loopy.app.input.GeteventReader
import com.loopy.app.input.TouchDevice
import com.loopy.app.macro.Macro
import com.loopy.app.macro.MacroStore
import com.loopy.app.macro.Stroke
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
import kotlin.math.hypot

/**
 * 매크로/플레이리스트 컨트롤 오버레이.
 *  - 녹화: getevent → RawRecorder(좌표 타임라인). 정지 시 자동 저장.
 *  - 재생: 저장 매크로 하나, 또는 플레이리스트(셔플백 + N회/무한)를 injectInputEvent 로 주입.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private val reader = GeteventReader()
    private val recorder = RawRecorder()
    private var device: TouchDevice? = null
    private var recording = false
    private var playJob: Job? = null

    private lateinit var bar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var fab: FabLogoView
    private lateinit var panel: LinearLayout
    private var expanded = false
    private var hintView: TextView? = null
    private var dimView: View? = null
    private var deepLocked = false
    private lateinit var status: TextView
    private lateinit var recordBtn: ImageButton
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
        buildOverlay()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildOverlay() {
        // 루트: 세로. 위=[FAB + 가로 슬림 패널], 아래=상태/힌트/목록.
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
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
        val moonBtn = iconBtn(R.drawable.ic_ov_moon, 0xFF6C7BFF.toInt(), 0x226C7BFF) {
            setDeepSLock(true)
            if (expanded) toggleExpand()
        }
        panel.addView(recordBtn)
        panel.addView(playBtn, marginLeft(dp(8)))
        panel.addView(listBtn, marginLeft(dp(8)))
        panel.addView(moonBtn, marginLeft(dp(8)))

        val hRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hRow.addView(fab, LinearLayout.LayoutParams(fabSize, fabSize))
        hRow.addView(panel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8) })

        status = TextView(this).apply {
            setTextColor(0xFF8A8DA0.toInt()); textSize = 11f; text = "녹화를 눌러 시작"
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

        bar.addView(hRow)
        bar.addView(status)
        bar.addView(stopPlayBtn)
        bar.addView(hintTv)

        barParams = baseParams().apply { x = dp(12); y = dp(80) }
        setupFabTouch()
        wm.addView(bar, barParams)
    }

    /** 원형 아이콘 버튼 — 아이콘 tint + 옅은 원형 배경으로 airy 하게. */
    private fun iconBtn(iconRes: Int, tint: Int, bgTint: Int, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            background = circleBg(bgTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val sz = dp(42)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { onClick() }
        }

    private fun circleBg(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(color)
    }

    /** Deep SLock: 검은 레이어(non-touchable, 매크로 통과) + 최소 밝기. 달 버튼/FAB 재탭으로 토글. */
    private fun setDeepSLock(on: Boolean) {
        if (on) {
            if (dimView != null) return
            val v = View(this).apply { setBackgroundColor(0xDB000000.toInt()) } // ~86% 블랙
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { screenBrightness = 0.01f } // 최소 밝기(권한 불필요)
            runCatching { wm.addView(v, p) }
            dimView = v
            // FAB(달 버튼)를 검은 레이어 위로 올려 다시 누를 수 있게
            runCatching { wm.removeViewImmediate(bar); wm.addView(bar, barParams) }
            fab.alpha = 0.4f // 희미하게
            deepLocked = true
        } else {
            dimView?.let { runCatching { wm.removeView(it) } }
            dimView = null
            fab.alpha = 1f
            deepLocked = false
        }
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
            listPanel?.let { bar.removeView(it); listPanel = null }
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
                        if (deepLocked) setDeepSLock(false) else toggleExpand()
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
        recordBtn.setImageResource(R.drawable.ic_ov_stop)
        status.text = "● 녹화중 — 평소처럼 플레이해"
        reader.stream(scope, listOf(dev)) { _, p -> recorder.onPoint(p) }
    }

    private fun stopRecord() {
        reader.stop()
        recording = false
        recordBtn.setImageResource(R.drawable.ic_ov_record)
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

    private fun startSingle(strokes: List<Stroke>, label: String) {
        if (recording) stopRecord()
        stopPlayback(null)
        if (strokes.isEmpty()) { status.text = "재생할 게 없어"; return }
        stopPlayBtn.visibility = View.VISIBLE
        status.text = "▶ 재생중… $label"
        playJob = scope.launch {
            runStrokes(strokes)
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
                    runStrokes(m.strokes)
                    if (pl.gapMs > 0) delay(pl.gapMs.toLong())
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

    /** 스트로크들을 현재 화면 방향에 맞춰 순차 주입. 취소 가능. */
    /** 모든 스트로크를 절대 시각(startMs) 기준으로 병합해 playMulti 로 한 번에 동시 재생. */
    private suspend fun runStrokes(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        val m = DisplayMetrics()
        displayObj.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels
        val rot = displayObj.rotation

        val nStroke = strokes.size
        // 손가락 id 배정: 겹치지 않는 스트로크끼리는 같은 id 재사용(활성 포인터 수 최소화).
        val fingerIds = IntArray(nStroke)
        val idFreeAt = ArrayList<Long>()
        for (k in strokes.indices) {
            val s = strokes[k]
            val end = s.startMs + maxOf(s.durationMs, s.samples.lastOrNull()?.t ?: 0L)
            var assigned = -1
            for (id in idFreeAt.indices) {
                if (idFreeAt[id] <= s.startMs) { assigned = id; break }
            }
            if (assigned == -1) { assigned = idFreeAt.size; idFreeAt.add(end) } else idFreeAt[assigned] = end
            fingerIds[k] = assigned
        }

        val totalSamples = strokes.sumOf { it.samples.size }
        val startArr = LongArray(nStroke)
        val durArr = LongArray(nStroke)
        val counts = IntArray(nStroke)
        val xs = IntArray(totalSamples)
        val ys = IntArray(totalSamples)
        val times = LongArray(totalSamples)
        var off = 0
        for (k in strokes.indices) {
            val s = strokes[k]
            startArr[k] = s.startMs
            durArr[k] = s.durationMs
            counts[k] = s.samples.size
            for (i in s.samples.indices) {
                val (px, py) = toPx(s.samples[i].nx, s.samples[i].ny, w, h, rot)
                xs[off] = px; ys[off] = py; times[off] = s.samples[i].t
                off++
            }
        }
        withContext(Dispatchers.IO) {
            LoopyService.playMulti(fingerIds, startArr, durArr, counts, xs, ys, times)
        }
    }

    // ── 저장 목록 (드롭다운: 플레이리스트 + 매크로) ──
    private fun toggleList() {
        listPanel?.let { bar.removeView(it); listPanel = null; return }
        val playlists = PlaylistStore.list(this)
        val macros = MacroStore.list(this)
        val lp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0)
        }
        if (playlists.isEmpty() && macros.isEmpty()) {
            lp.addView(hint("저장된 게 없어"))
        } else {
            if (playlists.isNotEmpty()) {
                lp.addView(hint("─ 플레이리스트 ─"))
                for (pl in playlists) {
                    lp.addView(listRow("▶▶ ${pl.name} (${pl.macroIds.size})", 0xFF6C7BFF.toInt()) {
                        toggleList(); playPlaylist(pl)
                    })
                }
            }
            if (macros.isNotEmpty()) {
                lp.addView(hint("─ 매크로 ─"))
                for (mac in macros) {
                    lp.addView(listRow("▶ ${mac.name} (${mac.strokes.size})", 0xFF2B2D42.toInt()) {
                        toggleList(); startSingle(mac.strokes, mac.name)
                    })
                }
            }
        }
        bar.addView(lp)
        listPanel = lp
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
        dimView?.let { runCatching { wm.removeView(it) } }
        runCatching { wm.removeView(bar) }
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/service/LoopyService.kt")"
cat > "app/src/main/java/com/loopy/app/service/LoopyService.kt" << 'LOOPY_EOF'
package com.loopy.app.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * 앱 프로세스에서 Shizuku UserService(LoopyUserService)를 바인딩하고 호출을 넘겨주는 싱글톤.
 * 실제 injectInputEvent 는 shell 프로세스(LoopyUserService)에서 일어난다.
 *
 * tap/swipe 은 바인더 호출이라 호출 스레드를 잠깐 붙잡으므로 IO 스레드에서 부를 것.
 * 반환값은 "서비스가 연결돼 있어 호출을 보냈는지" (false 면 아직 미연결/실패).
 */
object LoopyService {

    @Volatile private var svc: ILoopyService? = null
    @Volatile private var binding = false

    fun isReady(): Boolean = svc != null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            svc = if (binder != null && binder.pingBinder()) {
                ILoopyService.Stub.asInterface(binder)
            } else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    private fun args(context: Context) =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, LoopyUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("loopy")
            .debuggable(false)
            .version(1)

    /** Shizuku 권한이 허용된 뒤 호출. 이미 연결됐거나 진행 중이면 무시. */
    fun bind(context: Context) {
        if (svc != null || binding) return
        binding = true
        runCatching { Shizuku.bindUserService(args(context.applicationContext), conn) }
            .onFailure { binding = false }
    }

    fun playMulti(
        fingerIds: IntArray, startMs: LongArray, durationsMs: LongArray, sampleCounts: IntArray,
        xsFlat: IntArray, ysFlat: IntArray, timesFlat: LongArray,
    ): Boolean = runCatching {
        svc?.playMulti(fingerIds, startMs, durationsMs, sampleCounts, xsFlat, ysFlat, timesFlat); svc != null
    }.getOrDefault(false)
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/service/LoopyUserService.kt")"
cat > "app/src/main/java/com/loopy/app/service/LoopyUserService.kt" << 'LOOPY_EOF'
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
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/shizuku/Shell.kt")"
cat > "app/src/main/java/com/loopy/app/shizuku/Shell.kt" << 'LOOPY_EOF'
package com.loopy.app.shizuku

import rikka.shizuku.Shizuku

/**
 * Shizuku 셸 실행을 한 곳으로 모은 진입점.
 *
 * Shizuku.newProcess 는 이 API 버전에서 private 이라 리플렉션으로 호출한다.
 * (rikka.shizuku.* 는 안드로이드 프레임워크 클래스가 아니라 hidden-API 제약이 없고,
 *  debug 빌드라 난독화도 없어 안전하다. 반환형 ShizukuRemoteProcess 는
 *  java.lang.Process 를 상속하므로 Process 로 받아 쓴다.)
 */
object Shell {

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    /** raw 프로세스 생성. 스트리밍(getevent)처럼 살아있는 프로세스가 필요할 때 사용. */
    fun newProcess(cmd: Array<String>): Process =
        newProcessMethod.invoke(null, cmd, null, null) as Process

    private fun sh(cmd: String): Process = newProcess(arrayOf("sh", "-c", cmd))

    /** 명령을 실행하고 stdout 을 통째로 반환(동기). 실패 시 null. */
    fun run(cmd: String): String? = try {
        val p = sh(cmd)
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    } catch (t: Throwable) {
        null
    }

    /** 출력이 필요 없는 명령을 실행하고 끝날 때까지 대기(동기). */
    fun exec(cmd: String) {
        try {
            sh(cmd).waitFor()
        } catch (_: Throwable) {
        }
    }

    /** 진단용: 실행 후 exit 코드 + stderr + stdout 을 요약 문자열로 반환. */
    fun execDiag(cmd: String): String {
        return try {
            val p = sh(cmd)
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            buildString {
                append("exit=").append(code)
                if (err.isNotBlank()) append("\nERR: ").append(err.take(300))
                if (out.isNotBlank()) append("\nOUT: ").append(out.take(200))
            }
        } catch (t: Throwable) {
            "예외: ${t.message}"
        }
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/shizuku/ShizukuManager.kt")"
cat > "app/src/main/java/com/loopy/app/shizuku/ShizukuManager.kt" << 'LOOPY_EOF'
package com.loopy.app.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,   // 바인더 자체가 없음 (Shizuku 앱 미설치/미실행)
    NEEDS_PERMISSION,
    READY,
}

object ShizukuManager {

    private const val PERMISSION_CODE = 1001

    fun state(): ShizukuState {
        if (!Shizuku.pingBinder()) return ShizukuState.NOT_INSTALLED
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuState.READY
        } else {
            ShizukuState.NEEDS_PERMISSION
        }
    }

    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        if (!Shizuku.pingBinder()) {
            onResult(false)
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_CODE) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(PERMISSION_CODE)
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/ui/theme/Glass.kt")"
cat > "app/src/main/java/com/loopy/app/ui/theme/Glass.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 카드. 파스텔 라이트 테마에선 퓨어 화이트 + 부드러운 그림자(elevation)로 '공중에 뜬'
 * 클린 플랫 느낌을 낸다. 아주 얇은 차콜 테두리로 경계를 살짝 잡아준다.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier,
        color = LoopyCard,
        shape = shape,
        shadowElevation = 6.dp,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, CardStroke),
    ) {
        Column(
            modifier = Modifier.padding(padding),
            content = content,
        )
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/ui/theme/MeshGradient.kt")"
cat > "app/src/main/java/com/loopy/app/ui/theme/MeshGradient.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 오프화이트 배경 위에 파스텔(피치/라벤더/민트) 블롭을 크고 흐리게 겹쳐 아주 느리게
 * 움직여 mesh 느낌을 낸다. 밝은 배경이라 alpha 를 살짝 높여 은은하게 보이게 한다.
 * 재생 중에는 animate=false 로 꺼서 GPU/배터리를 아낀다.
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "mesh")
    val phase = if (animate) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(30_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        ).value
    } else 0f

    Canvas(modifier = modifier.fillMaxSize().background(LoopyBg)) {
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.85f

        blob(MeshPeach, w * (0.22f + 0.10f * cos(phase)), h * (0.18f + 0.08f * sin(phase)), r * 0.9f)
        blob(MeshMint, w * (0.82f + 0.10f * sin(phase * 0.9f)), h * (0.26f + 0.10f * cos(phase * 1.1f)), r * 0.85f)
        blob(MeshLavender, w * (0.55f + 0.12f * cos(phase * 1.3f)), h * (0.88f + 0.06f * sin(phase)), r)
        blob(MeshPeach, w * (0.12f + 0.08f * sin(phase * 0.7f)), h * (0.82f + 0.08f * cos(phase * 0.8f)), r * 0.6f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.blob(
    color: Color, cx: Float, cy: Float, radius: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.65f), color.copy(alpha = 0f)),
            center = Offset(cx, cy),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx, cy),
    )
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/ui/theme/Theme.kt")"
cat > "app/src/main/java/com/loopy/app/ui/theme/Theme.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Loopy 팔레트 — 오프화이트 베이스 + 파스텔 메쉬 (Soft Flow)
val LoopyBg = Color(0xFFF8F9FA)      // 소프트 스노우 배경
val LoopyCard = Color(0xFFFFFFFF)    // 퓨어 화이트 카드
val TextHi = Color(0xFF2B2D42)       // 딥 차콜 (주요 텍스트)
val TextLo = Color(0xFF8A8DA0)       // 뮤트 그레이 (보조 텍스트)

val Accent = Color(0xFF6C7BFF)       // 페리윙클 (강조/버튼 텍스트)
val LoopyViolet = Accent             // 기존 참조 호환용 별칭

// 파스텔 메쉬 3색
val MeshPeach = Color(0xFFFFB8B1)    // 피치 오로라 (녹화/액션)
val MeshLavender = Color(0xFFCDDAFD) // 소프트 라벤더 (연결)
val MeshMint = Color(0xFFB5E2FA)     // 민트 브리즈 (재생/루프)

val CardStroke = Color(0x142B2D42)   // 차콜 8% 테두리

// 순백 베이스 (리디자인) + 헤더 타이틀 그라데이션(페리윙클→민트)
val LoopyWhite = Color(0xFFFFFFFF)
val LoopySubtle = Color(0xFFF6F7FB)  // 카드 미묘한 광채용
val GradA = Color(0xFF6C7BFF)        // 페리윙클
val GradB = Color(0xFF5FD0E8)        // 민트(살짝 채도↑ 시원하게)

// 뉴모피즘 — 살짝 쿨한 오프화이트 베이스 + 밝은/어두운 이중 그림자
val NeuBase = Color(0xFFEEF1F7)      // 카드/배경 공통 베이스(그림자가 보이도록 순백보다 살짝 회색)
val NeuLight = Color(0xFFFFFFFF)     // 좌상단 하이라이트
val NeuDark = Color(0xFFC9D0E0)      // 우하단 그림자

private val LoopyColors = lightColorScheme(
    primary = Accent,
    secondary = MeshMint,
    background = LoopyBg,
    surface = LoopyCard,
    onPrimary = Color.White,
    onBackground = TextHi,
    onSurface = TextHi,
)

@Composable
fun LoopyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LoopyColors,
        typography = Typography(),
        content = content,
    )
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/java/com/loopy/app/ui/theme/Ui.kt")"
cat > "app/src/main/java/com/loopy/app/ui/theme/Ui.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 헤더 타이틀용 그라데이션(페리윙클→민트). */
val TitleBrush = Brush.linearGradient(listOf(GradA, GradB))

@Composable
fun GradientTitle(text: String, size: Int = 30, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(brush = TitleBrush, fontSize = size.sp, fontWeight = FontWeight.Bold),
    )
}

/**
 * 화면 바닥부터 약 40% 높이까지 은은하게 깔리는 애니메이션 그라데이션.
 * 색이 팔레트를 따라 천천히 순환한다. Box 안 맨 처음에 배치.
 */
@Composable
fun AnimatedBottomGradient(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "bg")
    val t by tr.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    val palette = listOf(
        GradA, GradB, Color(0xFFB9A7FF), Color(0xFFFFB4C6), GradA,
    )
    val idx = t * (palette.size - 1)
    val i = idx.toInt().coerceIn(0, palette.size - 2)
    val col = lerp(palette[i], palette[i + 1], idx - i)

    Canvas(modifier.fillMaxSize()) {
        val topY = size.height * 0.6f // 바닥에서 위로 40%
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, col.copy(alpha = 0.20f)),
                startY = topY, endY = size.height,
            ),
            topLeft = Offset(0f, topY),
            size = Size(size.width, size.height - topY),
        )
    }
}

/** 뉴모피즘 이중 그림자(좌상단 밝게 / 우하단 어둡게). */
private fun Modifier.neumorph(corner: Dp) = this.drawBehind {
    val r = corner.toPx()
    val off = 5.dp.toPx()
    val blur = 11.dp.toPx()
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
        val dark = android.graphics.Paint().apply {
            isAntiAlias = true
            color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, off, off, 0xFFC9D0E0.toInt())
        }
        fw.drawRoundRect(rect, r, r, dark)
        val light = android.graphics.Paint().apply {
            isAntiAlias = true
            color = 0xFFEEF1F7.toInt()
            setShadowLayer(blur, -off, -off, 0xFFFFFFFF.toInt())
        }
        fw.drawRoundRect(rect, r, r, light)
    }
}

/** 정통 뉴모피즘 카드 — 배경과 같은 톤 + 이중 그림자로 은은하게 떠 보인다. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .neumorph(cornerRadius)
            .clip(shape)
            .background(NeuBase)
            .padding(padding),
    ) {
        Column(content = content)
    }
}

/** 아주 얇은 라인 아이콘. kind: home / playlist / library / settings. */
@Composable
fun LineIcon(kind: String, color: Color, size: Dp = 24.dp, strokeWidth: Float = 2f) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        fun p(x: Float, y: Float) = Offset(x / 24f * s, y / 24f * s)
        val stroke = Stroke(width = strokeWidth / 24f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "home" -> {
                drawPath(Path().apply {
                    moveTo(p(3f, 11f).x, p(3f, 11f).y)
                    lineTo(p(12f, 3.5f).x, p(12f, 3.5f).y)
                    lineTo(p(21f, 11f).x, p(21f, 11f).y)
                }, color, style = stroke)
                drawPath(Path().apply {
                    moveTo(p(5.5f, 9.5f).x, p(5.5f, 9.5f).y)
                    lineTo(p(5.5f, 20f).x, p(5.5f, 20f).y)
                    lineTo(p(18.5f, 20f).x, p(18.5f, 20f).y)
                    lineTo(p(18.5f, 9.5f).x, p(18.5f, 9.5f).y)
                }, color, style = stroke)
                drawPath(Path().apply {
                    moveTo(p(10f, 20f).x, p(10f, 20f).y)
                    lineTo(p(10f, 14f).x, p(10f, 14f).y)
                    lineTo(p(14f, 14f).x, p(14f, 14f).y)
                    lineTo(p(14f, 20f).x, p(14f, 20f).y)
                }, color, style = stroke)
            }
            "playlist" -> {
                drawLine(color, p(4f, 7f), p(15f, 7f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 12f), p(15f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 17f), p(11f, 17f), stroke.width, StrokeCap.Round)
                drawLine(color, p(19f, 6f), p(19f, 16f), stroke.width, StrokeCap.Round)
                drawCircle(color, p(2.2f, 0f).x - p(0f, 0f).x, p(17f, 16.5f), style = stroke)
            }
            "library" -> {
                drawPath(Path().apply {
                    moveTo(p(3.5f, 7f).x, p(3.5f, 7f).y)
                    lineTo(p(9f, 7f).x, p(9f, 7f).y)
                    lineTo(p(11f, 9f).x, p(11f, 9f).y)
                    lineTo(p(20.5f, 9f).x, p(20.5f, 9f).y)
                    lineTo(p(20.5f, 18.5f).x, p(20.5f, 18.5f).y)
                    lineTo(p(3.5f, 18.5f).x, p(3.5f, 18.5f).y)
                    close()
                }, color, style = stroke)
            }
            "settings" -> {
                drawLine(color, p(4f, 7f), p(20f, 7f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 12f), p(20f, 12f), stroke.width, StrokeCap.Round)
                drawLine(color, p(4f, 17f), p(20f, 17f), stroke.width, StrokeCap.Round)
                val r = p(2.6f, 0f).x - p(0f, 0f).x
                drawCircle(NeuBase, r, p(15f, 7f)); drawCircle(color, r, p(15f, 7f), style = stroke)
                drawCircle(NeuBase, r, p(9f, 12f)); drawCircle(color, r, p(9f, 12f), style = stroke)
                drawCircle(NeuBase, r, p(16f, 17f)); drawCircle(color, r, p(16f, 17f), style = stroke)
            }
        }
    }
}
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/drawable/ic_launcher_background.xml")"
cat > "app/src/main/res/drawable/ic_launcher_background.xml" << 'LOOPY_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108"
                android:startColor="#6C7BFF"
                android:endColor="#5FD0E8" />
        </aapt:attr>
    </path>
</vector>
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/drawable/ic_launcher_foreground.xml")"
cat > "app/src/main/res/drawable/ic_launcher_foreground.xml" << 'LOOPY_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- 순환 원호 (약 300도, 위쪽에 갭) -->
    <path android:strokeColor="#FFFFFF" android:strokeWidth="7.5"
        android:fillColor="#00000000" android:strokeLineCap="round"
        android:pathData="M73.7,46.8 A21,21 0 1,1 60,34.2" />
    <!-- 화살촉 -->
    <path android:fillColor="#FFFFFF"
        android:pathData="M55,27 L67.5,30 L61,41 Z" />
</vector>
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/drawable/ic_ov_list.xml")"
cat > "app/src/main/res/drawable/ic_ov_list.xml" << 'LOOPY_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="2.2"
        android:strokeLineCap="round" android:pathData="M6,8 L18,8" />
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="2.2"
        android:strokeLineCap="round" android:pathData="M6,12 L18,12" />
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="2.2"
        android:strokeLineCap="round" android:pathData="M6,16 L13,16" />
</vector>
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/drawable/ic_ov_moon.xml")"
cat > "app/src/main/res/drawable/ic_ov_moon.xml" << 'LOOPY_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M20,14.5 A8.5,8.5 0 1,1 10.2,4.2 A6.6,6.6 0 0,0 20,14.5 Z" />
</vector>
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/drawable/ic_ov_play.xml")"
cat > "app/src/main/res/drawable/ic_ov_play.xml" << 'LOOPY_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M9,6.5 L18,12 L9,17.5 Z" />
</vector>
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/drawable/ic_ov_record.xml")"
cat > "app/src/main/res/drawable/ic_ov_record.xml" << 'LOOPY_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,12 m-6.5,0 a6.5,6.5 0 1,0 13,0 a6.5,6.5 0 1,0 -13,0" />
</vector>
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/drawable/ic_ov_stop.xml")"
cat > "app/src/main/res/drawable/ic_ov_stop.xml" << 'LOOPY_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M8,7 L16,7 Q17,7 17,8 L17,16 Q17,17 16,17 L8,17 Q7,17 7,16 L7,8 Q7,7 8,7 Z" />
</vector>
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml")"
cat > "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" << 'LOOPY_EOF'
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
LOOPY_EOF

mkdir -p "$(dirname "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml")"
cat > "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml" << 'LOOPY_EOF'
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
LOOPY_EOF

echo "소스+리소스 28개 동기화."
git add -A
git commit -m "Deep SLock: 검은 레이어+최소밝기 토글(달 버튼) + 녹화 아이콘 수정"
git push
echo "푸시 완료!"

