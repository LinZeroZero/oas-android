#!/usr/bin/env bash
# OAS 一键门禁（对应 docs/WORKFLOW.md §4）
#
#   bash tools/gate.sh            # 本地三项：①daemon 重编(按需) ②assembleDebug ⑤硬约束
#   bash tools/gate.sh --device   # 额外再跑 ③cv_selftest ④四段保活（要装机 + root）
#   bash tools/gate.sh --static   # 云端 CI 用：跳过①②③④（没有 Android SDK），
#                                 # 只跑 bash -n tools/*.sh + ⑤硬约束/AGP
#
# 末行固定输出 `GATE: PASS` 或 `GATE: FAIL <项名>`，便于直接当提交前的判据。
# 每项固定打印一行 `[n/5] OK|SKIP|FAIL ...`。
#
# 注意：故意不用 `set -e` —— 一项失败也要把后面几项的结论打出来，
#       否则「fail 在一半就退出」会让人误以为其他项是通过的。
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
cd "$ROOT" || exit 2

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android}"

PKG=org.onmyoji.oas.android
APK=app/build/outputs/apk/debug/app-debug.apk
JAR=app/src/main/assets/server/server.jar
STAMP=poc/displayserver/build/.gate-java.sum
CACHE_DEVICE=/data/user/0/$PKG/files/oas/cache

DEVICE=0
STATIC=0
for arg in "$@"; do
    case "$arg" in
        --device) DEVICE=1 ;;
        --static) STATIC=1 ;;
        -h|--help|help)
            sed -n '2,10p' "$0"
            exit 0
            ;;
        *)
            echo "未知参数: $arg（支持 --device / --static）" >&2
            exit 2
            ;;
    esac
done
# --static：云端 CI 没有 Android SDK，只跑语法 + 硬约束；两者互斥，static 优先
if [ "$STATIC" = "1" ]; then
    DEVICE=0
fi

FAILED=""

fail() {
    # 只记第一个失败项，末行用它做结论
    if [ -z "$FAILED" ]; then
        FAILED="$1"
    fi
}

root_shell() {
    su -c "$1" 2>/dev/null | tr -d '\r'
}

mkdir -p poc/displayserver/build build

# ---------------------------------------------------------------- #
# ① daemon（server.jar）：java 变了才重编
# ---------------------------------------------------------------- #
JAVA_SUM=$(find poc/displayserver/src -name '*.java' -print0 2>/dev/null |
    sort -z | xargs -0 cat 2>/dev/null | md5sum | cut -d' ' -f1)

if [ "$STATIC" = "1" ]; then
    echo "[1/5] SKIP  --static（云端无 Android SDK）"
else
    if [ -f "$STAMP" ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$JAVA_SUM" ] && [ -f "$JAR" ]; then
        echo "[1/5] SKIP  java 未变化（server.jar $(stat -c%s "$JAR" 2>/dev/null) B）"
    else
        if bash tools/build_displayserver.sh >poc/displayserver/build/gate-build.log 2>&1; then
            printf '%s\n' "$JAVA_SUM" >"$STAMP"
            echo "[1/5] OK    server.jar $(stat -c%s "$JAR" 2>/dev/null) B"
        else
            echo "[1/5] FAIL  build_displayserver（见 poc/displayserver/build/gate-build.log）"
            fail "daemon-build"
        fi
    fi
fi

# ---------------------------------------------------------------- #
# ② assembleDebug（必须用 ./gradlew；PATH 上的 gradle 4.31 会报 Task not found）
# ---------------------------------------------------------------- #
if [ "$STATIC" = "1" ]; then
    echo "[2/5] SKIP  --static（云端无 Android SDK）"
elif ./gradlew :app:assembleDebug >build/gate-gradle.log 2>&1; then
    if [ -f "$APK" ]; then
        echo "[2/5] OK    APK md5 $(md5sum "$APK" | cut -d' ' -f1) / $(stat -c%s "$APK") B"
    else
        echo "[2/5] FAIL  BUILD 通过但找不到 $APK"
        fail "apk-missing"
    fi
else
    echo "[2/5] FAIL  assembleDebug（见 build/gate-gradle.log）"
    fail "assembleDebug"
fi

# ---------------------------------------------------------------- #
# ③ cv_selftest 32/32（仅 --device）
# ---------------------------------------------------------------- #
if [ "$DEVICE" = "1" ]; then
    root_shell "rm -f $CACHE_DEVICE/cv_selftest.json"
    root_shell "am start -n $PKG/.MainActivity --ei cv_selftest 1" >/dev/null
    CV_OK=0
    i=0
    while [ "$i" -lt 90 ]; do
        sleep 2
        i=$((i + 2))
        RESULT=$(root_shell "cat $CACHE_DEVICE/cv_selftest.json")
        if [ -n "$RESULT" ]; then
            PASSED=$(printf '%s' "$RESULT" | grep -oE '"passed":[[:space:]]*[0-9]+' | grep -oE '[0-9]+' | head -1)
            TOTAL=$(printf '%s' "$RESULT" | grep -oE '"total":[[:space:]]*[0-9]+' | grep -oE '[0-9]+' | head -1)
            if [ -n "$PASSED" ] && [ "$PASSED" = "$TOTAL" ] && [ "${TOTAL:-0}" -ge 30 ]; then
                CV_OK=1
            fi
            break
        fi
    done
    if [ "$CV_OK" = "1" ]; then
        echo "[3/5] OK    cv_selftest $PASSED/$TOTAL"
    else
        echo "[3/5] FAIL  cv_selftest ${PASSED:-?}/${TOTAL:-?}（见 $CACHE_DEVICE/cv_selftest.txt）"
        fail "cv_selftest"
    fi
else
    echo "[3/5] SKIP  未加 --device"
fi

# ---------------------------------------------------------------- #
# ④ 四段保活 4/4 + C 段空洞 < 5000ms（仅 --device）
#    BG-01（app 侧驱动器会被 do_freezer_trap 冻住）未修前这一项预期 FAIL
# ---------------------------------------------------------------- #
if [ "$DEVICE" = "1" ]; then
    root_shell "rm -f $CACHE_DEVICE/keepalive_poc.txt $CACHE_DEVICE/keepalive_hb.jsonl"
    root_shell "am force-stop $PKG"
    root_shell "am start -n $PKG/.MainActivity --ei keepalive 1 --ei kb_a 30 --ei kb_b 60 --ei kb_c 60" >/dev/null
    # 20s 预热 + 30 + 60 + 60 + 15 + 收尾余量
    sleep 240
    REPORT=$(root_shell "cat $CACHE_DEVICE/keepalive_poc.txt")
    SCORE=$(printf '%s' "$REPORT" | grep -oE '通过 [0-9]+/[0-9]+' | head -1 | tr -d ' ')
    HOLE=$(printf '%s' "$REPORT" | grep -oE '空洞 [0-9]+ms' | grep -oE '[0-9]+' | sort -rn | head -1)
    if [ "$SCORE" = "通过4/4" ] && [ -n "$HOLE" ] && [ "$HOLE" -lt 5000 ]; then
        echo "[4/5] OK    四段 4/4，C 段最大空洞 ${HOLE}ms"
    else
        echo "[4/5] FAIL  四段 ${SCORE:-无报告}，C 段最大空洞 ${HOLE:-n/a}ms"
        fail "keepalive-4of4"
    fi
    root_shell "am force-stop $PKG"
else
    echo "[4/5] SKIP  未加 --device"
fi

# ---------------------------------------------------------------- #
# [S] 脚本语法检查（仅 --static；云端 CI 上这是唯一能跑的可执行检查）
# ---------------------------------------------------------------- #
if [ "$STATIC" = "1" ]; then
    SYNTAX_BAD=""
    for f in tools/*.sh; do
        bash -n "$f" 2>/dev/null || SYNTAX_BAD="$SYNTAX_BAD $f"
    done
    if [ -z "$SYNTAX_BAD" ]; then
        echo "[S]   OK    bash -n $(ls tools/*.sh 2>/dev/null | wc -l) 个脚本通过"
    else
        echo "[S]   FAIL  bash -n 语法错误:$SYNTAX_BAD"
        fail "shell-syntax"
    fi
fi

# ---------------------------------------------------------------- #
# ⑤ 硬约束（任何分支都不许破）
# ---------------------------------------------------------------- #
# 硬约束检查要先去掉注释：代码注释里写「不注册 BootReceiver」是合规说明，
# 但真的出现 BootReceiver/JobScheduler 标识符就是违规。这里把源码剥掉注释后放进
# build/gate-strip/ 再 grep，避免一整片假告警（第一版就栽在这）。
strip_comments() {
    awk '
    BEGIN { block = 0; xml = 0 }
    {
        line = $0
        out = ""
        while (1) {
            if (xml) {
                p = index(line, "-->")
                if (p == 0) { line = ""; break }
                line = substr(line, p + 3); xml = 0; continue
            }
            if (block) {
                p = index(line, "*/")
                if (p == 0) { line = ""; break }
                line = substr(line, p + 2); block = 0; continue
            }
            q = index(line, "<!--")
            c = index(line, "/*")
            s = index(line, "//")
            if (q > 0 && (c == 0 || q < c) && (s == 0 || q < s)) {
                out = out substr(line, 1, q - 1)
                line = substr(line, q + 4); xml = 1; continue
            }
            if (c > 0 && (s == 0 || c < s)) {
                out = out substr(line, 1, c - 1)
                line = substr(line, c + 2); block = 1; continue
            }
            if (s > 0) {
                out = out substr(line, 1, s - 1)
                break
            }
            out = out line
            break
        }
        print out
    }' "$1" >"$2"
}

STRIP=build/gate-strip
rm -rf "$STRIP"
find app/src poc/displayserver/src -type f \( -name '*.kt' -o -name '*.java' -o -name '*.xml' \) 2>/dev/null |
    while read -r f; do
        dst="$STRIP/$f"
        mkdir -p "$(dirname "$dst")"
        strip_comments "$f" "$dst"
    done

HITS=""
for pat in 'wm size[^a-zA-Z"[:space:]]*[0-9]' 'wm size -[0-9]' 'BootReceiver' \
    'JobScheduler' 'AlarmManager' 'RECEIVE_BOOT_COMPLETED'; do
    out=$(grep -rInE "$pat" "$STRIP" 2>/dev/null)
    if [ -n "$out" ]; then
        HITS="$HITS
$out"
    fi
done
# 同一行可能被多个关键词命中，去重后再判
HITS=$(printf '%s\n' "$HITS" | sed '/^$/d' | sort -u)

AGP=$(grep -E '^agp[[:space:]]*=' gradle/libs.versions.toml 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
GRADLE_WRAPPER=$(grep -E '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties 2>/dev/null |
    grep -oE 'gradle-[0-9.]+' | head -1)

if [ -n "$HITS" ]; then
    echo "[5/5] FAIL  硬约束命中：$HITS"
    fail "hard-constraints"
elif [ "$AGP" != "8.13.2" ]; then
    echo "[5/5] FAIL  AGP 版本是 ${AGP:-未知}，必须是 8.13.2（9.x 会让 Chaquopy 静默失效）"
    fail "agp-version"
else
    echo "[5/5] OK    无禁用符号，AGP $AGP / $GRADLE_WRAPPER"
fi

# ---------------------------------------------------------------- #
echo "---"
if [ -n "$FAILED" ]; then
    echo "GATE: FAIL $FAILED"
    exit 1
fi
echo "GATE: PASS"
exit 0
