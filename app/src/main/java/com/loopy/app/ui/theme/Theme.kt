package com.loopy.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Loopy 팔레트 — 다크 베이스 + 보라/청록 블롭
val LoopyBg = Color(0xFF07070C)
val LoopyViolet = Color(0xFF7C5CFF)
val LoopyBlue = Color(0xFF3B82F6)
val LoopyCyan = Color(0xFF22D3EE)
val LoopyPink = Color(0xFFEC4899)
val GlassStroke = Color(0x26FFFFFF)   // 흰색 15%
val GlassFill = Color(0x14FFFFFF)     // 흰색 8%
val TextHi = Color(0xFFF3F3F7)
val TextLo = Color(0xFF9A9AB0)

private val LoopyColors = darkColorScheme(
    primary = LoopyViolet,
    secondary = LoopyCyan,
    background = LoopyBg,
    surface = LoopyBg,
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
