package com.loopy.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 디자인 토큰.
 *
 * 화면이 색이나 여백을 직접 정하지 않는다. 값이 화면마다 흩어지면 톤이 어긋나고,
 * 다크 모드를 넣을 때 모든 화면을 다시 뒤져야 한다.
 *
 * 뉴모피즘은 표면색 하나에서 그림자와 글로우를 파생시킨다. 그래서 팔레트만 바꾸면
 * 라이트와 다크가 같은 코드로 동작한다.
 */
data class Palette(
    val dark: Boolean,

    /** 배경이자 표면. 뉴모피즘에서 둘은 같은 재질이다. */
    val surface: Color,

    /** 표면보다 한 단계 파인 곳(입력 필드, 트랙). */
    val sunken: Color,

    val accent: Color,
    val accentSoft: Color,

    val textStrong: Color,
    val textMuted: Color,

    val success: Color,
    val danger: Color,

    /** 강조 텍스트용. 두 색 사이를 흐른다. */
    val gradientStart: Color,
    val gradientEnd: Color,
) {
    /**
     * 뉴모피즘 그림자.
     *
     * 라이트에서는 표면을 어둡게, 다크에서는 더 어둡게. 값이 다른 이유는 어두운 배경에서
     * 같은 비율로 낮추면 거의 검정이 되어 그림자가 구멍처럼 보이기 때문이다.
     */
    val shadow: Color
        get() = if (dark) surface.shade(0.55f) else surface.shade(0.80f)

    /**
     * 뉴모피즘 글로우.
     *
     * 다크에서 흰색을 쓰면 배경보다 너무 밝아 테두리처럼 도드라진다. 표면을 밝힌 색이라야
     * "같은 재질에 빛이 닿은" 느낌이 난다.
     */
    val glow: Color
        get() = if (dark) surface.tint(0.10f) else Color.White
}

val LightPalette = Palette(
    dark = false,
    surface = Color(0xFFEEF1F7),
    sunken = Color(0xFFE4E8F0),
    accent = Color(0xFF5B8DEE),
    accentSoft = Color(0xFF8FB4F5),
    textStrong = Color(0xFF2B2D42),
    textMuted = Color(0xFF8A8DA0),
    success = Color(0xFF20C997),
    danger = Color(0xFFE06A6A),
    gradientStart = Color(0xFF5B8DEE),
    gradientEnd = Color(0xFF5FD0E8),
)

val DarkPalette = Palette(
    dark = true,
    surface = Color(0xFF2A2D35),
    sunken = Color(0xFF23262D),
    accent = Color(0xFF5B8DEE),
    accentSoft = Color(0xFF3E6CC4),
    textStrong = Color(0xFFE8EAF0),
    textMuted = Color(0xFF8A8DA0),
    success = Color(0xFF20C997),
    danger = Color(0xFFE06A6A),
    gradientStart = Color(0xFF6D9BF0),
    gradientEnd = Color(0xFF6FDAF0),
)

/** 여백. 임의의 숫자를 쓰지 않는다. 리듬이 깨지면 화면이 어수선해 보인다. */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val huge: Dp = 48.dp
}

object Radius {
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
}

/** 뉴모피즘 깊이. offset 과 blur 는 함께 움직여야 자연스럽다. */
enum class Depth(val offset: Dp, val blur: Dp) {
    /** 리스트 행처럼 살짝 뜬 것. */
    SM(2.dp, 5.dp),

    /** 카드, 버튼. */
    MD(4.dp, 10.dp),

    /** 떠 있는 버튼, 시트. */
    LG(6.dp, 16.dp),
}

object Type {
    val title: TextUnit = 24.sp
    val heading: TextUnit = 18.sp
    val body: TextUnit = 14.sp
    val caption: TextUnit = 12.sp
    val label: TextUnit = 11.sp
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** 어디서든 팔레트를 꺼낸다. 화면이 색을 직접 아는 일이 없어야 한다. */
val palette: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

fun Color.shade(f: Float) = Color(red * f, green * f, blue * f, alpha)

fun Color.tint(f: Float) =
    Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)
