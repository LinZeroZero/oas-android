#!/usr/bin/env bash
# 把 AGP 用的 aapt2 换成 ARM64 版本（在 arm64 Linux / proot 里构建 APK 必需）。
#
# 背景：Google 只发布 x86_64 的 aapt2 可执行文件，arm64 机器上跑会报
#   "AAPT2 ... Daemon startup failed"
# 做法：把 tools/aapt2/aapt2-arm64-v8a 塞进 Gradle 缓存里的 aapt2 jar，
#       以及已经解压出来的 transforms 目录。
#
# 用法：bash tools/patch_aapt2.sh
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/aapt2/aapt2-arm64-v8a"
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"

if [[ ! -f "$SRC" ]]; then
  echo "找不到 ARM64 aapt2: $SRC" >&2
  exit 1
fi
chmod +x "$SRC"

count=0
while IFS= read -r -d '' jar; do
  dir=$(dirname "$jar")
  cp "$SRC" "$dir/aapt2"
  chmod +x "$dir/aapt2"
  (cd "$dir" && zip -q -f "$(basename "$jar")" aapt2)
  echo "patched jar: $jar"
  count=$((count + 1))
done < <(find "$GRADLE_CACHE/modules-2/files-2.1/com.android.tools.build/aapt2" \
          -name 'aapt2-*-linux*.jar' -type f -print0 2>/dev/null)

while IFS= read -r -d '' bin; do
  cp "$SRC" "$bin"
  chmod +x "$bin"
  echo "patched bin: $bin"
  count=$((count + 1))
done < <(find "$GRADLE_CACHE" -path '*transforms*' -name 'aapt2' -type f -print0 2>/dev/null)

echo "done, patched $count target(s)"
