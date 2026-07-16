#!/usr/bin/env bash
set -e

# 블록을 스크래치식으로: 렌더/스냅/폭
#  1) 렌더 -> 불투명 평면 + 얇은 진한 테두리 + 옅은 그림자 하나. (거대한 색 번짐 bloom 제거)
#     블록끼리 비쳐 뭉개지던 문제 해결. 퍼즐처럼 또렷이 맞물린다.
#  2) 스냅 -> 자석처럼 널널하게(snapX 90 / snapY 64). 대충 근처에 놓아도 붙는다.
#  3) 폭 -> 200dp 고정 대신 내용에 맞춰 늘어남. 대기는 0.3 처럼 뒷자리 0 제거,
#     10.789 처럼 길면 블록이 늘어남. 편집은 소수점 키패드.
#
# 바뀐 부분만(diff) git apply 로 반영한다. 현재 코드에 안 맞으면 커밋하지 않고 멈춘다.

PATCH="$(mktemp)"
cat > "$PATCH" << 'LOOPY_PATCH_EOF'
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt b/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
index c7a9575..ff1eb54 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
@@ -17,6 +17,7 @@ import androidx.compose.foundation.layout.offset
 import androidx.compose.foundation.layout.padding
 import androidx.compose.foundation.layout.size
 import androidx.compose.foundation.layout.width
+import androidx.compose.foundation.layout.widthIn
 import androidx.compose.foundation.rememberScrollState
 import androidx.compose.foundation.verticalScroll
 import androidx.compose.material3.Text
@@ -297,7 +298,6 @@ private fun BlockView(
 ) {
     val spec = specOf(material.typeId)
     val density = LocalDensity.current
-    val w = 200.dp
     val h = blockHeightDp(material)
 
     val px = with(density) { (material.meta.x * zoom + camera.x).roundToInt() }
@@ -314,7 +314,9 @@ private fun BlockView(
             // 끌고 있는 블록이 맨 위에 있어야 어디로 가는지 보인다. 그림자는 요소 밖으로
             // 뻗어야 하므로 레이어로 가둘 수 없고, 대신 겹침 순서로 정리한다.
             .zIndex(if (lifted) 10f else 0f)
-            .size(w, h)
+            // 폭은 내용에 맞춰 늘어난다(스크래치처럼). 숫자가 길어지면 블록이 길어진다.
+            .height(h)
+            .widthIn(min = 132.dp)
             .blockShape(
                 shape = spec.shape,
                 color = spec.color,
@@ -336,9 +338,8 @@ private fun BlockView(
     ) {
         Row(
             Modifier
-                .fillMaxWidth()
                 .height(52.dp)
-                .padding(horizontal = Space.md),
+                .padding(start = Space.md, end = Space.lg),
             verticalAlignment = Alignment.CenterVertically,
         ) {
             LoopyIcon(spec.icon, Color.White, size = 15.dp)
@@ -377,7 +378,7 @@ private fun defaultParams(typeId: String) = when (typeId) {
 }
 
 private fun detailOf(m: Material): String = when (val pr = m.params) {
-    is WaitParams -> "%.3f초".format(pr.ms / 1000.0)
+    is WaitParams -> fmtSec(pr.ms) + "초"
     is LoopParams -> if (pr.infinite) "무한" else "${pr.count}번"
     is IfParams -> pr.condition.ifEmpty { "조건 없음" }
     else -> ""
@@ -416,8 +417,14 @@ private fun SlotChip(text: String, rounded: Boolean) {
     }
 }
 
+/** 초를 사람이 읽기 좋게: 뒷자리 0을 떼어 0.300 -> 0.3, 1.000 -> 1, 10.789 -> 10.789. */
+private fun fmtSec(ms: Long): String {
+    val v = java.math.BigDecimal.valueOf(ms).movePointLeft(3).stripTrailingZeros()
+    return if (v.signum() == 0) "0" else v.toPlainString()
+}
+
 private fun slotText(m: Material): String = when (val pr = m.params) {
-    is WaitParams -> "%.3f".format(pr.ms / 1000.0)
+    is WaitParams -> fmtSec(pr.ms)
     is LoopParams -> if (pr.infinite) "∞" else pr.count.toString()
     is IfParams -> pr.condition.ifEmpty { "?" }
     is com.loopy.app.core.material.BrightnessParams -> pr.level.toString()
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockDraw.kt b/app/src/main/java/com/loopy/app/blocks/BlockDraw.kt
index b9cda6c..70dd86d 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockDraw.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockDraw.kt
@@ -7,6 +7,7 @@ import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.graphics.Path
 import androidx.compose.ui.graphics.asAndroidPath
 import androidx.compose.ui.graphics.drawscope.DrawScope
+import androidx.compose.ui.graphics.drawscope.Stroke
 import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
 import androidx.compose.ui.graphics.nativeCanvas
 import androidx.compose.ui.graphics.toArgb
@@ -19,12 +20,9 @@ import com.loopy.app.ui.theme.palette
  * 모양이 문법이다. 모자 블록은 위가 둥글어 무엇도 위에 붙일 수 없고, 마개 블록은 아래가
  * 평평해 뒤에 이어붙일 수 없다. 규칙을 설명하는 대신 손으로 만져 알게 한다.
  *
- * 블록은 배경과 다른 색이므로 뉴모피즘의 "같은 재질" 규칙 밖에 있다. 회색 그림자만 씌우면
- * 색이 탁해지고 스티커처럼 얹힌 것 같다. 그래서 자기 색을 물감이 번지듯 퍼뜨린다. 번짐은
- * 블록 모양을 그대로 따라가므로 모서리에서 어긋나지 않는다.
- *
- * 그리는 순서가 중요하다. 번짐을 먼저 깔고 본체를 마지막에 한 번만 칠한다. 번짐 위에 본체를
- * 덧칠하지 않으면 경계 안쪽이 잘려 이중 테가 생긴다.
+ * 렌더는 스크래치를 따른다: 불투명 평면 + 얇은 진한 테두리 + 아주 옅은 드롭 그림자 하나.
+ * 예전엔 자기 색을 크게 번지게(bloom) 그렸는데, 블록을 딱 물려 놓으면 번짐끼리 겹쳐
+ * 반투명하게 뭉개졌다. 스크래치 블록은 서로 비치지 않는 불투명 조각이라 또렷이 맞물린다.
  */
 fun Modifier.blockShape(
     shape: BlockShape,
@@ -32,41 +30,26 @@ fun Modifier.blockShape(
     innerTop: Float = 0f,
     innerHeight: Float = 0f,
     lifted: Boolean = false,
-): Modifier = composed {
-    val p = palette
-    drawBehind {
-        val path = blockPath(shape, size.width, size.height, innerTop, innerHeight)
-        val off = (if (lifted) 10.dp else 5.dp).toPx()
-        val blur = (if (lifted) 24.dp else 13.dp).toPx()
-
-        drawIntoCanvas { canvas ->
-            val fw = canvas.nativeCanvas
-            val native = path.asAndroidPath()
+): Modifier = drawBehind {
+    // 스크래치식: 불투명 평면 + 얇은 진한 테두리 + 아주 옅은 드롭 그림자 하나.
+    // 예전엔 자기 색을 크게 번지게(bloom) 그려서, 블록을 물려 놓으면 번짐끼리 겹쳐
+    // 반투명하게 뭉개졌다. 스크래치 블록은 서로 비치지 않는 불투명 조각이라 또렷이 맞물린다.
+    val path = blockPath(shape, size.width, size.height, innerTop, innerHeight)
 
-            val bloom = android.graphics.Paint().apply {
-                isAntiAlias = true
-                this.color = color.toArgb()
-                setShadowLayer(blur * 1.9f, 0f, off * 0.5f, color.copy(alpha = 0.6f).toArgb())
-            }
-            fw.drawPath(native, bloom)
-
-            val dark = android.graphics.Paint().apply {
-                isAntiAlias = true
-                this.color = color.toArgb()
-                setShadowLayer(blur, off, off, p.shadowColor.copy(alpha = 0.65f).toArgb())
-            }
-            fw.drawPath(native, dark)
-
-            val light = android.graphics.Paint().apply {
-                isAntiAlias = true
-                this.color = color.toArgb()
-                setShadowLayer(blur * 0.8f, -off * 0.7f, -off * 0.7f, p.light.copy(alpha = 0.5f).toArgb())
-            }
-            fw.drawPath(native, light)
+    val elev = (if (lifted) 6.dp else 2.dp).toPx()
+    val sblur = (if (lifted) 12.dp else 5.dp).toPx()
+    drawIntoCanvas { canvas ->
+        val body = android.graphics.Paint().apply {
+            isAntiAlias = true
+            this.color = color.toArgb()
+            setShadowLayer(sblur, 0f, elev, android.graphics.Color.argb(if (lifted) 90 else 56, 0, 0, 0))
         }
-
-        drawPath(path, color)
+        canvas.nativeCanvas.drawPath(path.asAndroidPath(), body)
     }
+
+    // 얇은 진한 테두리 — 블록색을 어둡게. 또렷함은 여기서 나온다.
+    val edge = Color(color.red * 0.70f, color.green * 0.70f, color.blue * 0.70f, 1f)
+    drawPath(path, edge, style = Stroke(width = 1.5.dp.toPx()))
 }
 
 /**
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt b/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
index 43456c7..e8c4cca 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
@@ -99,8 +99,9 @@ fun flattenStacks(stacks: List<Material>): List<Material> {
  * 건너뛰고, 체인의 맨 끝을 찾아간다.
  */
 fun snapStacks(stacks: MutableList<Material>) {
-    val snapX = 44f
-    val snapY = 40f
+    // 스크래치처럼 자석같이 붙게 넓게 잡는다. 노치를 대충 근처에 놓아도 빨려 붙는다.
+    val snapX = 90f
+    val snapY = 64f
 
     for (i in stacks.indices) {
         val m = stacks[i]
LOOPY_PATCH_EOF

if git apply --check "$PATCH"; then
  git apply "$PATCH"
  rm -f "$PATCH"
  echo "패치 적용 완료"
  git add -A
  git commit -m "블록 스크래치식: 불투명 평면 렌더(bloom 제거), 스냅 넓힘, 폭 내용맞춤, 대기 0제거"
  git push
  echo "푸시 완료 - Actions에서 빌드 확인하세요"
else
  rm -f "$PATCH"
  echo "패치가 현재 코드에 맞지 않아 커밋하지 않았습니다."
  echo "알려주시면 전체파일 방식으로 다시 드릴게요."
  exit 1
fi

