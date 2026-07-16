#!/usr/bin/env bash
set -e

# 촬영 매크로 배치/표시 수정
#  1) 겹쳐 보이던 문제 -> 열 때마다 깔끔히 물린 세로 줄기로 재배치(옛 좌표 무시).
#     블록 간격을 meshStep(높이-노치2겹)으로 통일 -> 열림/스냅이 똑같이 물린다.
#  2) parallel -> 동시 블록은 본체 줄기에, 갈래는 오른쪽에 떠서 선으로 연결.
#  3) 기다리기 표시를 소수점 3자리로(0.300), 입력은 소수점 키패드.
#
# 바뀐 부분만(diff) git apply 로 반영한다. 현재 코드에 안 맞으면 커밋하지 않고 멈춘다.

PATCH="$(mktemp)"
cat > "$PATCH" << 'LOOPY_PATCH_EOF'
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt b/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
index b12f652..c7a9575 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockCanvas.kt
@@ -377,7 +377,7 @@ private fun defaultParams(typeId: String) = when (typeId) {
 }
 
 private fun detailOf(m: Material): String = when (val pr = m.params) {
-    is WaitParams -> "%.1f초".format(pr.ms / 1000.0)
+    is WaitParams -> "%.3f초".format(pr.ms / 1000.0)
     is LoopParams -> if (pr.infinite) "무한" else "${pr.count}번"
     is IfParams -> pr.condition.ifEmpty { "조건 없음" }
     else -> ""
@@ -417,7 +417,7 @@ private fun SlotChip(text: String, rounded: Boolean) {
 }
 
 private fun slotText(m: Material): String = when (val pr = m.params) {
-    is WaitParams -> "%.1f".format(pr.ms / 1000.0)
+    is WaitParams -> "%.3f".format(pr.ms / 1000.0)
     is LoopParams -> if (pr.infinite) "∞" else pr.count.toString()
     is IfParams -> pr.condition.ifEmpty { "?" }
     is com.loopy.app.core.material.BrightnessParams -> pr.level.toString()
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt b/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
index 8b95c56..43456c7 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockLayout.kt
@@ -25,36 +25,51 @@ const val NOTCH_DEPTH = 5f
  * 갈래도 결국 하나의 Material 이고 공리를 늘리지 않는 편이 낫기 때문이다.
  */
 
-/** 저장된 좌표가 없으면 세로로 늘어놓는다. 자동 배치는 첫 인상만 책임진다. */
+/**
+ * 트리를 캔버스에 배치한다.
+ *
+ * 열 때마다 **깔끔히 물린 세로 줄기로 다시 정렬**한다. 예전엔 저장된 좌표가 있으면 그대로
+ * 썼는데(hasPos), 옛 버전에서 겹쳐 저장된 좌표를 계속 물고 와 촬영 매크로가 뭉개져 보였다.
+ * 순서(실행 순서)는 children 순서로 보존되므로, 좌표를 다시 흘려도 논리는 그대로다.
+ *
+ * 동시 블록은 본체 줄기에 남고, 갈래만 오른쪽에 떠서 선으로 이어진다. 갈래를 줄기 사이에
+ * 끼우면 세로 흐름이 끊기기 때문이다.
+ */
 fun layoutStacks(build: Material): List<Material> {
     val out = ArrayList<Material>()
+    val x = 40f
     var y = 40f
 
     for (child in build.children) {
         if (child.typeId == "parallel") {
-            val node = if (hasPos(child)) child else child.copy(meta = child.meta.copy(x = 40f, y = y))
-            out.add(node.copy(children = emptyList()))
-            y += 150f
+            val node = child.copy(children = emptyList(), meta = child.meta.copy(x = x, y = y))
+            out.add(node)
 
-            // 갈래는 동시 블록 바로 아래에, 왼쪽부터 오른쪽으로 나란히. 예전엔 -80 에서
-            // 시작해 갈래가 화면 왼쪽 밖으로 밀려나고 앞 블록과 겹쳤다.
-            var bx = node.meta.x
+            // 갈래는 줄기 오른쪽에 위에서 아래로. 몇 개든 늘어도 본체 흐름을 막지 않는다.
+            var by = y
             for (branch in child.children) {
-                val b = if (hasPos(branch)) branch else branch.copy(meta = branch.meta.copy(x = bx, y = y))
-                out.add(b.copy(meta = b.meta.copy(note = node.id)))
-                bx += 220f
+                out.add(branch.copy(meta = branch.meta.copy(x = x + 280f, y = by, note = node.id)))
+                by += blockHeight(branch) + 20f
             }
-            y += 120f
+
+            // 다음 블록은 동시 블록 바로 아래에 물린다(갈래 아래가 아니라).
+            y += meshStep(node)
         } else {
-            val placed = if (hasPos(child)) child else child.copy(meta = child.meta.copy(x = 40f, y = y))
+            val placed = child.copy(meta = child.meta.copy(x = x, y = y))
             out.add(placed)
-            y += 60f
+            y += meshStep(placed)
         }
     }
     return out
 }
 
-private fun hasPos(m: Material) = m.meta.x != 0f || m.meta.y != 0f
+/**
+ * 다음 블록이 앞 블록에 물리도록 내려갈 거리.
+ *
+ * 높이에서 노치 두 겹을 빼면 아래 볼록이 위 오목에 딱 들어간다. 스냅(snapStacks)도 같은
+ * 계산을 써야 열었을 때와 끌어 붙였을 때가 어긋나지 않는다.
+ */
+fun meshStep(m: Material): Float = blockHeight(m) - NOTCH_DEPTH * 2f
 
 /**
  * 캔버스를 다시 트리로.
@@ -86,9 +101,6 @@ fun flattenStacks(stacks: List<Material>): List<Material> {
 fun snapStacks(stacks: MutableList<Material>) {
     val snapX = 44f
     val snapY = 40f
-    // 위 블록의 볼록 혀가 아래 블록의 오목 홈을 완전히 채우려면 노치 깊이의 두 배만큼 겹쳐야
-    // 한다. 한 번(=노치 깊이)만 겹치면 혀 절반이 홈 밖에 남아 사이가 벌어져 보인다.
-    val overlap = NOTCH_DEPTH * 2f
 
     for (i in stacks.indices) {
         val m = stacks[i]
@@ -105,7 +117,7 @@ fun snapStacks(stacks: MutableList<Material>) {
             if (specOf(other.typeId).shape == BlockShape.CAP) continue
 
             // 이미 아래에 무언가 붙어 있으면 그 자리는 찼다.
-            val bottom = other.meta.y + blockHeight(other) - overlap
+            val bottom = other.meta.y + meshStep(other)
             val taken = stacks.any { k ->
                 k.id != m.id && k.id != other.id &&
                     abs(k.meta.x - other.meta.x) < 4f && abs(k.meta.y - bottom) < 4f
@@ -125,7 +137,7 @@ fun snapStacks(stacks: MutableList<Material>) {
             stacks[i] = m.copy(
                 meta = m.meta.copy(
                     x = other.meta.x,
-                    y = other.meta.y + blockHeight(other) - overlap,
+                    y = other.meta.y + meshStep(other),
                 ),
             )
             return
diff --git a/app/src/main/java/com/loopy/app/blocks/BlockPalette.kt b/app/src/main/java/com/loopy/app/blocks/BlockPalette.kt
index 5bbb09f..1b530f5 100644
--- a/app/src/main/java/com/loopy/app/blocks/BlockPalette.kt
+++ b/app/src/main/java/com/loopy/app/blocks/BlockPalette.kt
@@ -243,7 +243,11 @@ fun BlockParamSheet(
                         singleLine = true,
                         textStyle = TextStyle(color = p.textStrong, fontSize = Type.body),
                         keyboardOptions = KeyboardOptions(
-                            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
+                            keyboardType = when {
+                                material.params is WaitParams -> KeyboardType.Decimal
+                                numeric -> KeyboardType.Number
+                                else -> KeyboardType.Text
+                            },
                         ),
                         cursorBrush = SolidColor(p.accent),
                         modifier = Modifier.fillMaxWidth(),
LOOPY_PATCH_EOF

if git apply --check "$PATCH"; then
  git apply "$PATCH"
  rm -f "$PATCH"
  echo "패치 적용 완료"
  git add -A
  git commit -m "촬영 매크로: 물린 세로 배치로 재정렬, parallel 갈래 오른쪽 분리, 대기 3자리 표시/소수 입력"
  git push
  echo "푸시 완료 - Actions에서 빌드 확인하세요"
else
  rm -f "$PATCH"
  echo "패치가 현재 코드에 맞지 않아 커밋하지 않았습니다."
  echo "알려주시면 전체파일 방식으로 다시 드릴게요."
  exit 1
fi

