#!/data/data/com.termux/files/usr/bin/bash
# MainActivity 2/2 (이어붙임)
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더"; exit 1; fi
cat >> "app/src/main/java/com/loopy/app/MainActivity.kt" << 'LOOPY_EOF'
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
                Text("설정 탭에서 Shizuku·오버레이 권한을 먼저 허용해줘.", color = TextLo, fontSize = 11.sp)
            }
        }

        VideoSessionCard(sessionActive, onToggleSession)

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
            Text("상태바에 화면 녹화(캐스트) 아이콘이 떠 있는 건 정상이야.", color = TextLo, fontSize = 11.sp)
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
echo "2/2 완료."
git add -A
git commit -m "편집기 1단계: 영상 프리뷰+그라데이션 스트로크 오버레이+싱크 재생, 라이브러리 편집 버튼"
git push
echo "푸시 완료!"

