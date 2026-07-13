package com.loopy.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 디자인 토큰.
 *
 * 뉴모피즘의 규칙은 단순하지만 어기기 쉽다.
 *
 *  1. 배경과 표면은 **같은 색**이다. 카드가 배경보다 밝거나 어두우면 그 순간 뉴모피즘이 아니다.
 *  2. 깊이는 **오직 두 그림자**에서 나온다. 좌상 밝게, 우하 어둡게. 요소 안에 그라데이션을 깔면
 *     "면 안에 그림자가 생긴" 이상한 물건이 된다.
 *  3. 광원은 **항상 왼쪽 위**. 요소마다 방향이 다르면 화면이 부서져 보인다.
 *  4. 그림자만으로는 버튼임을 알 수 없다. 인터랙티브 요소에는 **악센트 색이나 테두리**가 필요하다.
 *     이 스타일이 접근성으로 비판받는 이유가 그것이고, 하이브리드로 쓰면 해결된다.
 *
 * 색은 CSS 표준 레시피를 따른다. 밝은 배경(#E0E5EC)이라야 흰 하이라이트와 회청색 그림자가
 * 둘 다 보인다. 너무 밝으면 그림자가 사라지고, 너무 어두우면 하이라이트가 사라진다.
 */
data class Palette(
    val dark: Boolean,

    /** 배경이자 표면. 둘은 같은 재질이므로 같은 색이어야 한다. */
    val surface: Color,

    /** 좌상 하이라이트. 다크에서 흰색을 쓰면 딱딱한 플라스틱이 된다. */
    val light: Color,

    /** 우하 그림자. 다크에서 검정을 쓰면 구멍처럼 보인다. */
    val shadowColor: Color,

    /** 인터랙티브 요소의 유일한 단서. 하나만 쓴다. */
    val accent: Color,
    val accentSoft: Color,

    /** 본문 대비는 7:1 이상이어야 한다. 부드러운 회색은 예뻐 보이지만 읽을 수 없다. */
    val textStrong: Color,
    val textMuted: Color,

    val success: Color,
    val danger: Color,

    val gradientStart: Color,
    val gradientEnd: Color,
)

val LightPalette = Palette(
    dark = false,
    surface = Color(0xFFE0E5EC),
    light = Color(0xFFFFFFFF),
    shadowColor = Color(0xFFA3B1C6),
    accent = Color(0xFF5B8DEE),
    accentSoft = Color(0xFF8FB4F5),
    textStrong = Color(0xFF1F2430),
    textMuted = Color(0xFF6B7280),
    success = Color(0xFF20C997),
    danger = Color(0xFFE06A6A),
    gradientStart = Color(0xFF5B8DEE),
    gradientEnd = Color(0xFF5FD0E8),
)

val DarkPalette = Palette(
    dark = true,
    surface = Color(0xFF2A2D32),
    light = Color(0xFF34383E),
    shadowColor = Color(0xFF1C1E21),
    accent = Color(0xFF6D9BF0),
    accentSoft = Color(0xFF3E6CC4),
    textStrong = Color(0xFFEDEFF3),
    textMuted = Color(0xFF9BA1AC),
    success = Color(0xFF20C997),
    danger = Color(0xFFE06A6A),
    gradientStart = Color(0xFF6D9BF0),
    gradientEnd = Color(0xFF6FDAF0),
)

object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** 12~20dp. 각지면 몰딩 느낌이 깨지고, 너무 둥글면 풍선이 된다. */
object Radius {
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
}

/**
 * 그림자 깊이.
 *
 * 표준 레시피는 offset 8 / blur 16 이다. offset 이 blur 의 절반쯤일 때 가장 자연스럽다.
 * 작으면 평평해 보이고, 크면 배경에서 떨어져 나온 것처럼 보여 뉴모피즘이 아니게 된다.
 */
enum class Depth(val offset: Dp, val blur: Dp) {
    SM(4.dp, 8.dp),
    MD(8.dp, 16.dp),
    LG(10.dp, 20.dp),
}

object Type {
    val title: TextUnit = 28.sp
    val heading: TextUnit = 20.sp
    val body: TextUnit = 16.sp
    val caption: TextUnit = 13.sp
    val label: TextUnit = 12.sp
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

val palette: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

fun Color.shade(f: Float) = Color(red * f, green * f, blue * f, alpha)

fun Color.tint(f: Float) =
    Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)
