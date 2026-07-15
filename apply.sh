#!/usr/bin/env bash
set -e

# Build 화면 블록 3버그 수정
#  1) 퍼즐이 안 물림 -> 겹침(overlap)을 노치 깊이 2배로. 노치 깊이를 NOTCH_DEPTH 한 곳에서 정의.
#  2) C블록 스냅 높이 어긋남 -> 높이를 blockHeightDp 한 곳으로 통합(스냅=렌더).
#  3) parallel 연결 이상 -> 연결선 시작점을 실제 부모 높이로, 갈래를 음수 x 없이 블록 아래 나란히.
#
# 바뀐 부분만(diff) git apply 로 반영한다. 현재 코드에 안 맞으면 커밋하지 않고 멈춘다.

PATCH="$(mktemp)"
cat > "$PATCH" << 'LOOPY_PATCH_EOF'
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt b/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
index c5413ba..b12f652 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
@@ -383,18 +383,8 @@ private fun detailOf(m: Material): String = when (val pr = m.params) {
     else -> ""
 }
 
-private fun blockHeightDp(m: Material): Dp = when (specOf(m.typeId).shape) {
-    BlockShape.C_BLOCK -> 52.dp + innerHeightDp(m) + 30.dp
-    BlockShape.HAT -> 62.dp
-    else -> 52.dp
-}
-
-private fun innerHeightDp(m: Material): Dp =
-    if (specOf(m.typeId).shape == BlockShape.C_BLOCK) {
-        (m.children.size * 52).dp.coerceAtLeast(30.dp)
-    } else {
-        0.dp
-    }
+// 블록 높이는 BlockLayout.blockHeightDp / innerHeightDp 한 곳에서 정의한다.
+// 스냅과 렌더가 같은 값을 보게 하려는 것이므로 여기서 따로 계산하지 않는다.
 
 /**
  * 입력 홈.
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockDraw.kt b/app/src/main/java/com/loopy/app/blocks/BlockDraw.kt
index 3a5f526..b9cda6c 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockDraw.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockDraw.kt
@@ -84,7 +84,8 @@ private fun DrawScope.blockPath(
 ): Path {
     val cr = 8.dp.toPx()
     val nw = 26.dp.toPx()
-    val nd = 5.dp.toPx()
+    // 그려지는 노치 깊이와 스냅이 계산하는 겹침이 같은 값을 봐야 블록이 맞물린다.
+    val nd = NOTCH_DEPTH.dp.toPx()
     val nl = 18.dp.toPx()
     val wall = 14.dp.toPx()
 
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt b/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
index 5f282cc..8b95c56 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
@@ -3,9 +3,19 @@ package com.loopy.app.blocks
 import androidx.compose.ui.geometry.Offset
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.graphics.drawscope.DrawScope
+import androidx.compose.ui.unit.Dp
+import androidx.compose.ui.unit.dp
 import com.loopy.app.core.material.Material
 import kotlin.math.abs
 
+/**
+ * 노치(요철) 깊이. 블록이 맞물리는 유일한 치수 기준.
+ *
+ * [BlockDraw] 가 이 값으로 볼록/오목을 그리고, 스냅이 이 값으로 겹침을 계산한다. 한 곳에서만
+ * 정의해야 "그려지는 모양"과 "붙는 위치"가 어긋나지 않는다.
+ */
+const val NOTCH_DEPTH = 5f
+
 /**
  * 캔버스 배치.
  *
@@ -26,11 +36,13 @@ fun layoutStacks(build: Material): List<Material> {
             out.add(node.copy(children = emptyList()))
             y += 150f
 
-            var bx = -80f
+            // 갈래는 동시 블록 바로 아래에, 왼쪽부터 오른쪽으로 나란히. 예전엔 -80 에서
+            // 시작해 갈래가 화면 왼쪽 밖으로 밀려나고 앞 블록과 겹쳤다.
+            var bx = node.meta.x
             for (branch in child.children) {
                 val b = if (hasPos(branch)) branch else branch.copy(meta = branch.meta.copy(x = bx, y = y))
                 out.add(b.copy(meta = b.meta.copy(note = node.id)))
-                bx += 230f
+                bx += 220f
             }
             y += 120f
         } else {
@@ -74,7 +86,9 @@ fun flattenStacks(stacks: List<Material>): List<Material> {
 fun snapStacks(stacks: MutableList<Material>) {
     val snapX = 44f
     val snapY = 40f
-    val overlap = 5f
+    // 위 블록의 볼록 혀가 아래 블록의 오목 홈을 완전히 채우려면 노치 깊이의 두 배만큼 겹쳐야
+    // 한다. 한 번(=노치 깊이)만 겹치면 혀 절반이 홈 밖에 남아 사이가 벌어져 보인다.
+    val overlap = NOTCH_DEPTH * 2f
 
     for (i in stacks.indices) {
         val m = stacks[i]
@@ -119,13 +133,30 @@ fun snapStacks(stacks: MutableList<Material>) {
     }
 }
 
-/** 블록 하나의 높이. C블록은 안이 비어도 최소 높이를 갖는다. */
-fun blockHeight(m: Material): Float = when (specOf(m.typeId).shape) {
-    BlockShape.C_BLOCK -> 52f + 34f + 30f
-    BlockShape.HAT -> 62f
-    else -> 52f
+/**
+ * 블록 높이(dp) — 단일 출처.
+ *
+ * 스냅 계산과 실제 렌더가 같은 값을 봐야 블록이 맞물린다. 예전엔 스냅은 여기서, 렌더는
+ * BlockView 에서 각자 계산해 C블록에서 어긋났다(스냅 116 vs 렌더 112~). 이제 두 쪽이
+ * 모두 이 함수를 파생해 쓴다.
+ */
+fun blockHeightDp(m: Material): Dp = when (specOf(m.typeId).shape) {
+    BlockShape.C_BLOCK -> 52.dp + innerHeightDp(m) + 30.dp
+    BlockShape.HAT -> 62.dp
+    else -> 52.dp
 }
 
+/** C블록이 자식을 품는 홈의 높이. 자식 수에 따라 늘고, 비어도 최소치를 갖는다. */
+fun innerHeightDp(m: Material): Dp =
+    if (specOf(m.typeId).shape == BlockShape.C_BLOCK) {
+        (m.children.size * 52).dp.coerceAtLeast(30.dp)
+    } else {
+        0.dp
+    }
+
+/** 스냅 계산용 높이(dp 값). 렌더 높이와 반드시 같다. */
+fun blockHeight(m: Material): Float = blockHeightDp(m).value
+
 /** 희미한 격자. 있는 줄도 모를 만큼 옅어야 하지만, 없으면 평면이 어디로 뻗는지 알 수 없다. */
 fun DrawScope.drawGrid(color: Color, camera: Offset, zoom: Float) {
     val step = 32f * zoom
@@ -154,9 +185,11 @@ fun DrawScope.drawForkLinks(
     for (f in forks) {
         val parent = stacks.firstOrNull { it.id == f.meta.note } ?: continue
 
+        // 부모(동시 블록) 아래 가운데에서 시작한다. 높이를 하드코딩(52)하지 않고 실제 높이를
+        // 쓴다. 부모가 C블록이거나 높이가 다르면 하드코딩 값은 선이 블록 중간에서 튀어나온다.
         val from = Offset(
             (parent.meta.x + 100f) * zoom + camera.x,
-            (parent.meta.y + 52f) * zoom + camera.y,
+            (parent.meta.y + blockHeight(parent)) * zoom + camera.y,
         )
         val to = Offset(
             (f.meta.x + 100f) * zoom + camera.x,
LOOPY_PATCH_EOF

if git apply --check "$PATCH"; then
  git apply "$PATCH"
  rm -f "$PATCH"
  echo "패치 적용 완료"
  git add -A
  git commit -m "블록 수정: 퍼즐 물림(overlap=노치x2), 높이 단일화, parallel 연결선 앵커/갈래 배치"
  git push
  echo "푸시 완료 - Actions에서 빌드 확인하세요"
else
  rm -f "$PATCH"
  echo "패치가 현재 코드에 맞지 않아 커밋하지 않았습니다."
  echo "알려주시면 전체파일 방식으로 다시 드릴게요."
  exit 1
fi

