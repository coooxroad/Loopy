package com.loopy.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Loopy 팔레트 — 오프화이트 베이스 + 파스텔 메쉬 (Soft Flow)
val LoopyCard = Color(0xFFFFFFFF)    // 퓨어 화이트 카드
val TextHi = Color(0xFF2B2D42)       // 딥 차콜 (주요 텍스트)
val TextLo = Color(0xFF8A8DA0)       // 뮤트 그레이 (보조 텍스트)

val Accent = Color(0xFF6C7BFF)       // 페리윙클 (강조/버튼 텍스트)

// 파스텔 메쉬 3색
val MeshPeach = Color(0xFFFFB8B1)    // 피치 오로라 (녹화/액션)
val MeshLavender = Color(0xFFCDDAFD) // 소프트 라벤더 (연결)
val MeshMint = Color(0xFFB5E2FA)     // 민트 브리즈 (재생/루프)

val CardStroke = Color(0x142B2D42)   // 차콜 8% 테두리

// 순백 베이스 (리디자인) + 헤더 타이틀 그라데이션(페리윙클→민트)
val GradA = Color(0xFF6C7BFF)        // 페리윙클
val GradB = Color(0xFF5FD0E8)        // 민트(살짝 채도↑ 시원하게)

// 뉴모피즘 — 살짝 쿨한 오프화이트 베이스 + 밝은/어두운 이중 그림자
val NeuBase = Color(0xFFEEF1F7)      // 카드/배경 공통 베이스(그림자가 보이도록 순백보다 살짝 회색)

private val LoopyColors = lightColorScheme(
    primary = Accent,
    secondary = MeshMint,
    background = NeuBase,
    surface = LoopyCard,
    onPrimary = Color.White,
    onBackground = TextHi,
    onSurface = TextHi,
)

/**
 * 앱 테마.
 *
 * 팔레트를 CompositionLocal 로 흘려보내므로, 어떤 화면도 색을 직접 알 필요가 없다.
 * 다크 모드는 팔레트를 갈아끼우는 것으로 끝난다. 뉴모피즘의 그림자와 글로우가 표면색에서
 * 파생되기 때문이다.
 */
@Composable
fun LoopyTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = if (dark) {
                darkColorScheme(
                    primary = p.accent,
                    background = p.surface,
                    surface = p.surface,
                    onPrimary = Color.White,
                    onBackground = p.textStrong,
                    onSurface = p.textStrong,
                )
            } else {
                lightColorScheme(
                    primary = p.accent,
                    background = p.surface,
                    surface = p.surface,
                    onPrimary = Color.White,
                    onBackground = p.textStrong,
                    onSurface = p.textStrong,
                )
            },
            typography = Typography(),
            content = content,
        )
    }
}
