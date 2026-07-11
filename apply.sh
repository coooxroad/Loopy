#!/data/data/com.termux/files/usr/bin/bash
# mig.sh 마무리: Manifest에 Application 등록 + 커밋/푸시
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi

M="app/src/main/AndroidManifest.xml"

if grep -q 'android:name=".LoopyApp"' "$M"; then
  echo "이미 등록됨"
else
  # <application 다음 줄에 삽입 (sed의 a 명령은 이스케이프 문제가 없음)
  sed -i '/<application/a\        android:name=".LoopyApp"' "$M"
  echo "Manifest 등록 완료"
fi

echo "--- 확인 ---"
grep -n -A1 "<application" "$M" | head -3

echo "--- 생성된 파일 확인 ---"
for f in core/io/ShizukuIo.kt core/exec/TouchExecutor.kt data/MaterialStore.kt data/Migration.kt LoopyApp.kt; do
  p="app/src/main/java/com/loopy/app/$f"
  if [ -f "$p" ]; then echo "  OK $f"; else echo "  없음! $f"; fi
done

git add -A
git commit -m "Phase 0-2: ShizukuIo(캡처/재생/셸 단일 통로), MaterialStore(+schema), 구버전 마이그레이션(플레이리스트->빌드, gap->대기, cycles->반복), TouchExecutor, LoopyApp 등록"
git push
echo "푸시 완료"

