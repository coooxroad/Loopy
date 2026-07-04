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
