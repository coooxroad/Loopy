#!/data/data/com.termux/files/usr/bin/bash
# Loopy fix: 녹화 아이콘 잘린 원 수정
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi
mkdir -p app/src/main/res/drawable
cat > app/src/main/res/drawable/ic_ov_record.xml << 'LOOPY_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,12 m-6.5,0 a6.5,6.5 0 1,0 13,0 a6.5,6.5 0 1,0 -13,0" />
</vector>
LOOPY_EOF
echo "반영."
git add -A
git commit -m "fix: 녹화 아이콘 원 중심 보정(잘림 수정)"
git push
echo "푸시 완료!"

