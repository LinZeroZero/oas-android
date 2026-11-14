#!/usr/bin/env bash
# 构建 displayserver（root app_process 用的 dex 包）
#
#   javac(--release 8, bootclasspath=android.jar) -> d8 -> server.jar(内含 classes.dex)
#
# 用法：
#   bash tools/build_displayserver.sh
# 产物：
#   poc/displayserver/build/server.jar
#   app/src/main/assets/server/server.jar   （打进 APK，运行时解压到 filesDir）
#
# 为什么要单独编：这份代码要在 root 的 app_process 里跑，不能混进 app 的 dex，
# 否则会连带把 Compose / Chaquopy 的初始化带进来。
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
SRC="$ROOT/poc/displayserver/src"
OUT="$ROOT/poc/displayserver/build"
ASSETS="$ROOT/app/src/main/assets/server"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/35.0.0}"
D8="$BUILD_TOOLS/d8"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "找不到 android.jar: $ANDROID_JAR" >&2
  echo "请先 export ANDROID_HOME=\$HOME/Android" >&2
  exit 1
fi
if [ ! -x "$D8" ]; then
  echo "找不到 d8: $D8" >&2
  exit 1
fi

rm -rf "$OUT/classes" "$OUT/dex" "$OUT/server.jar"
mkdir -p "$OUT/classes" "$OUT/dex"

find "$SRC" -name '*.java' > "$OUT/sources.txt"
COUNT=$(wc -l < "$OUT/sources.txt")
echo "[1/3] javac: $COUNT 个源文件"
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" "@$OUT/sources.txt" || exit 1

echo "[2/3] d8: $(find "$OUT/classes" -name '*.class' | wc -l) 个 class"
# shellcheck disable=SC2046
"$D8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') || exit 1

echo "[3/3] 打包 server.jar"
if command -v zip >/dev/null 2>&1; then
  ( cd "$OUT/dex" && zip -q -j "$OUT/server.jar" classes.dex ) || exit 1
else
  python3 - "$OUT/dex/classes.dex" "$OUT/server.jar" <<'PY' || exit 1
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(src, 'classes.dex')
PY
fi

mkdir -p "$ASSETS"
cp -f "$OUT/server.jar" "$ASSETS/server.jar"

echo "---"
ls -l "$OUT/server.jar" "$ASSETS/server.jar"
echo "OK"
