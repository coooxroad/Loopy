package com.loopy.app

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopy.app.core.material.Material
import com.loopy.app.shizuku.ShizukuState
import com.loopy.app.ui.theme.Accent
import com.loopy.app.ui.theme.CardStroke
import com.loopy.app.ui.theme.SoftCard
import com.loopy.app.ui.theme.LoopyCard
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
import com.loopy.app.ui.theme.MeshLavender
import com.loopy.app.ui.theme.MeshMint
import com.loopy.app.ui.theme.MeshPeach
import com.loopy.app.ui.theme.TextHi
import com.loopy.app.ui.theme.TextLo

/**
 * 앱의 화면 탭들 — 대시보드 · 라이브러리 · 설정.
 *
 * MainActivity 가 액티비티 준비·권한·네비게이션·화면 렌더를 한꺼번에 맡아 698줄이었다. 화면 자체는
 * 상태를 전부 파라미터로 받으므로(단방향 흐름) 여기로 옮겨도 동작이 같다.
 */

@Composable
fun ScreenColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
fun DashboardTab(
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
fun LibraryTab(
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
fun SettingsTab(
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
fun VideoSessionCard(active: Boolean, onToggle: (Boolean) -> Unit) {
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
fun LoopyButton(text: String, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val base = Modifier.fillMaxWidth().height(50.dp).clip(shape).alpha(if (enabled) 1f else 0.45f)
    val styled = if (filled) base.background(Brush.horizontalGradient(listOf(MeshPeach, MeshLavender, MeshMint)))
    else base.background(LoopyCard).border(1.dp, CardStroke, shape)
    val clickMod = if (enabled) styled.clickable { onClick() } else styled
    Box(clickMod, contentAlignment = Alignment.Center) {
        Text(text, color = if (filled) TextHi else Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
