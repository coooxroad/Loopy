#!/data/data/com.termux/files/usr/bin/bash
# Loopy: 죽은 코드 정리(MeshGradient/Glass 삭제 + 안 쓰는 색 제거)
set -e

if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

rm -f "app/src/main/java/com/loopy/app/ui/theme/MeshGradient.kt"
rm -f "app/src/main/java/com/loopy/app/ui/theme/Glass.kt"

cat > "app/src/main/java/com/loopy/app/ui/theme/Theme.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

@Composable
fun LoopyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LoopyColors,
        typography = Typography(),
        content = content,
    )
}
LOOPY_EOF

echo "반영."
git add -A
git commit -m "정리: 죽은 코드 제거(MeshGradient/Glass, 미사용 색)"
git push
echo "푸시 완료!"

