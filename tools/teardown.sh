#!/usr/bin/env bash
# 收工定序（AGENTS §5）。真机实验结束后跑，缺一不算完。
#
# 用法：
#   bash tools/teardown.sh            # 真执行 + 逐项核账
#   bash tools/teardown.sh --dry-run  # 只打印将要执行的命令
#
# 末行固定输出 `TEARDOWN: PASS` 或 `TEARDOWN: FAIL <项>`，便于当判据。
# 设计约束：脚本里出现的包名/路径必须与仓库现状一致（gate ⑨ 会查本文件里反引号路径）。
set -u

APP_PKG="org.onmyoji.oas.android"
GAME_PKG="com.netease.onmyoji"
OPERIT_PKG="com.ai.assistance.operit/.ui.main.MainActivity"
SD_PATTERN="/sdcard/oas_*"
TMP_PATTERN="/data/local/tmp/oas_*"

DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

FAILED=""
fail() { [ -z "$FAILED" ] && FAILED="$1"; }

# 优先直接跑（在 Android shell 里）；在 proot 里则经 su -c
run() {
    if [ -n "${ANDROID_ROOT_AVAILABLE:-}" ] && [ -z "${PREFIX:-}" ]; then
        eval "$1"
    else
        su -c "$1" 2>/dev/null | tr -d '\r'
    fi
}
say() {
    if [ "$DRY" = "1" ]; then echo "[dry] $*"; else echo "\$ $*"; run "$*"; fi
}

echo "== ① 停应用/游戏 =="
say "am force-stop $APP_PKG"
say "am force-stop $GAME_PKG"
if [ "$DRY" = "0" ]; then
    sleep 1
    P1=$(run "pidof $APP_PKG || true"); P2=$(run "pidof $GAME_PKG || true")
    echo "  app pid='${P1:-none}' game pid='${P2:-none}'"
    [ -z "$P1" ] && [ -z "$P2" ] || fail "process-still-alive"
fi

echo "== ② 虚拟屏应为 0 =="
if [ "$DRY" = "1" ]; then echo "[dry] dumpsys window displays | grep -c 'DisplayInfo{\"oas\"'"; else
    N=$(run "dumpsys window displays 2>/dev/null | grep -c 'DisplayInfo{\"oas\"' || true")
    N=${N:-0}
    echo "  oas 屏数=$N（虚拟屏随进程退出销毁；若不为 0 需人工查 daemon）"
    [ "$N" = "0" ] || fail "virtual-display-left"
fi

echo "== ③ rotation 复位 =="
say "settings put system accelerometer_rotation 0"
say "settings put system user_rotation 0"
if [ "$DRY" = "0" ]; then
    R=$(run "settings get system user_rotation")
    echo "  user_rotation=$R"; [ "$R" = "0" ] || fail "rotation-not-reset"
fi

echo "== ④ 清临时文件 =="
say "rm -f $SD_PATTERN"
say "rm -f $TMP_PATTERN"
if [ "$DRY" = "0" ]; then
    L1=$(run "ls $SD_PATTERN 2>/dev/null | wc -l"); L2=$(run "ls $TMP_PATTERN 2>/dev/null | wc -l")
    echo "  /sdcard 残留=${L1:-0} /data/local/tmp 残留=${L2:-0}"
    [ "${L1:-0}" = "0" ] && [ "${L2:-0}" = "0" ] || fail "temp-files-left"
fi

echo "== ⑤ 杀自起后台进程 =="
if [ "$DRY" = "1" ]; then echo "[dry] pgrep -f 'logcat -s' → kill"; else
    K=$(run "pgrep -f 'logcat -s' | tr '\n' ' '" || true)
    if [ -n "${K// /}" ]; then
        echo "  发现 logcat 后台进程: $K → kill"
        run "pkill -f 'logcat -s' || true"
        sleep 1
        K2=$(run "pgrep -f 'logcat -s' | tr '\n' ' '" || true)
        [ -z "${K2// /}" ] || fail "logcat-still-running"
    else
        echo "  logcat 后台进程: 无"
    fi
fi

echo "== ⑥ Operit 回前台 =="
say "am start -n $OPERIT_PKG"
if [ "$DRY" = "0" ]; then
    sleep 1
    F=$(run "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
    echo "  $F"
    case "$F" in
        *com.ai.assistance.operit*) ;;
        *) fail "operit-not-foreground" ;;
    esac
fi

echo "---"
if [ -n "$FAILED" ]; then echo "TEARDOWN: FAIL $FAILED"; exit 1; fi
echo "TEARDOWN: PASS"
exit 0