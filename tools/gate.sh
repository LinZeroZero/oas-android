#!/usr/bin/env bash
# OAS 一键门禁（对应 docs/WORKFLOW.md §4）
#
#   bash tools/gate.sh            # 本地三项：①daemon 重编(按需) ②assembleDebug ⑤硬约束
#   bash tools/gate.sh --device   # 额外再跑 ③cv_selftest ④四段保活（要装机 + root）
#   bash tools/gate.sh --static   # 云端 CI 用：跳过①②③④（没有 Android SDK），
#                                 # 只跑 bash -n tools/*.sh + ⑤硬约束/AGP
#
# 末行固定输出 `GATE: PASS` 或 `GATE: FAIL <项名>`，便于直接当提交前的判据。
# 每项固定打印一行 `[n/8] OK|SKIP|FAIL ...`。
# ⑥ 状态同步：改了 app/ 或 poc/ 必须同时改 PROGRESS.md（规矩见 AGENTS.md）。
# ⑦ 状态文件行数上限（AGENTS ≤50 / PROGRESS ≤40；见 DECISIONS.md D-015）。
# ⑧ PROGRESS.md 不许写死过期 PR 号：文中每个 `#<数字>` 必须能在 `git log --oneline -100` 里找到（见 D-017）。
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
            sed -n '2,12p' "$0"
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
    echo "[1/8] SKIP  --static（云端无 Android SDK）"
else
    if [ -f "$STAMP" ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$JAVA_SUM" ] && [ -f "$JAR" ]; then
        echo "[1/8] SKIP  java 未变化（server.jar $(stat -c%s "$JAR" 2>/dev/null) B）"
    else
        if bash tools/build_displayserver.sh >poc/displayserver/build/gate-build.log 2>&1; then
            printf '%s\n' "$JAVA_SUM" >"$STAMP"
            echo "[1/8] OK    server.jar $(stat -c%s "$JAR" 2>/dev/null) B"
        else
            echo "[1/8] FAIL  build_displayserver（见 poc/displayserver/build/gate-build.log）"
            fail "daemon-build"
        fi
    fi
fi

# ---------------------------------------------------------------- #
# ② assembleDebug（必须用 ./gradlew；PATH 上的 gradle 4.31 会报 Task not found）
# ---------------------------------------------------------------- #
if [ "$STATIC" = "1" ]; then
    echo "[2/8] SKIP  --static（云端无 Android SDK）"
elif ./gradlew :app:assembleDebug >build/gate-gradle.log 2>&1; then
    if [ -f "$APK" ]; then
        echo "[2/8] OK    APK md5 $(md5sum "$APK" | cut -d' ' -f1) / $(stat -c%s "$APK") B"
    else
        echo "[2/8] FAIL  BUILD 通过但找不到 $APK"
        fail "apk-missing"
    fi
else
    echo "[2/8] FAIL  assembleDebug（见 build/gate-gradle.log）"
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
            # set -u 下 `[ "$PASSED" = "$TOTAL" ]` 在两者未赋值时会直接杀掉脚本，
            # 连末行 GATE: 都打不出来（BG-13）。所以先补默认值再做数值比较。
            P=${PASSED:-0}
            T=${TOTAL:-0}
            case "$P$T" in
                *[!0-9]* | "") ;; # 非数字 → 保持 CV_OK=0，不参与比较
                *)
                    if [ "$T" -ge 30 ] && [ "$P" = "$T" ]; then
                        CV_OK=1
                    fi
                    ;;
            esac
            break
        fi
    done
    if [ "$CV_OK" = "1" ]; then
        echo "[3/8] OK    cv_selftest ${PASSED:-0}/${TOTAL:-0}"
    else
        echo "[3/8] FAIL  cv_selftest ${PASSED:-0}/${TOTAL:-0}（见 $CACHE_DEVICE/cv_selftest.txt）"
        fail "cv_selftest"
    fi
else
    echo "[3/8] SKIP  未加 --device"
fi

# ---------------------------------------------------------------- #
# ④ 四段保活 4/4 + C 段空洞 < 5000ms（仅 --device）
#    BG-01（app 侧驱动器会被 do_freezer_trap 冻住）未修前这一项预期 FAIL
# ---------------------------------------------------------------- #
if [ "$DEVICE" = "1" ]; then
    root_shell "rm -f $CACHE_DEVICE/keepalive_poc.txt $CACHE_DEVICE/keepalive_hb.jsonl"
    root_shell "am force-stop $PKG"
    root_shell "am start -n $PKG/.MainActivity --ei keepalive 1 --ei kb_a 30 --ei kb_b 60 --ei kb_c 60" >/dev/null
    # 轮询等待报告定稿（BG-13：原来固定 sleep 240 只剩 55s 余量，机器一忙就误报）。
    # 判定依据：报告存在、含「通过 X/Y」且**不再出现「（进行中）」**（那是渐进报告的标记）。
    KEEPALIVE_TIMEOUT=420
    WAITED=0
    DONE=0
    REPORT=""
    while [ "$WAITED" -lt "$KEEPALIVE_TIMEOUT" ]; do
        sleep 5
        WAITED=$((WAITED + 5))
        REPORT=$(root_shell "cat $CACHE_DEVICE/keepalive_poc.txt")
        if [ -n "$REPORT" ] &&
            printf '%s' "$REPORT" | grep -q '通过 [0-9][0-9]*/[0-9][0-9]*' &&
            ! printf '%s' "$REPORT" | grep -q '进行中'; then
            DONE=1
            break
        fi
    done
    SCORE=$(printf '%s' "$REPORT" | grep -oE '通过 [0-9]+/[0-9]+' | head -1 | tr -d ' ')
    HOLE=$(printf '%s' "$REPORT" | grep -oE '空洞 [0-9]+ms' | grep -oE '[0-9]+' | sort -rn | head -1)
    if [ "$DONE" != "1" ]; then
        echo "[4/8] FAIL  四段报告 ${KEEPALIVE_TIMEOUT}s 内未定稿（${SCORE:-无报告}，末态 $WAITED s）"
        fail "keepalive-timeout"
    elif [ "$SCORE" = "通过4/4" ] && [ -n "${HOLE:-}" ] && [ "${HOLE:-0}" -lt 5000 ]; then
        echo "[4/8] OK    四段 4/4，C 段最大空洞 ${HOLE}ms"
    else
        echo "[4/8] FAIL  四段 ${SCORE:-无报告}，C 段最大空洞 ${HOLE:-n/a}ms"
        fail "keepalive-4of4"
    fi
    root_shell "am force-stop $PKG"
else
    echo "[4/8] SKIP  未加 --device"
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
        # 整行注释：shell(#) / yml(#) 直接丢掉（Kotlin/Java/xml 里不会以 # 开头）
        if (!block && !xml && line ~ /^[[:space:]]*#/) {
            print ""
            next
        }
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
# 扫描范围（BG-13：原来只扫 app/src 与 poc 的源码，漏了 tools/、*.gradle.kts、CI yml）。
# vendor/ 是上游代码（GPL 第三方），不参与本项目硬约束检查，见 docs/UPSTREAM.md。
{
    find app/src poc/displayserver/src tools .github -type f \
        \( -name '*.kt' -o -name '*.java' -o -name '*.xml' -o -name '*.kts' \
        -o -name '*.gradle' -o -name '*.yml' -o -name '*.yaml' -o -name '*.sh' \) 2>/dev/null
    find . -maxdepth 1 -type f \( -name '*.gradle' -o -name '*.gradle.kts' \) 2>/dev/null
} | grep -v '^\(\./\)\{0,1\}vendor/' |
    while read -r f; do
        dst="$STRIP/$f"
        mkdir -p "$(dirname "$dst")"
        strip_comments "$f" "$dst"
    done

HITS=""
# wm size 的三种违规写法必须分开匹配（BG-13）：
#   ① 带空格再跟数字：`wm size 1280x720`
#   ② 带空格再跟负数：`wm size -100x100`
#   ③ 带 reset：`wm size reset`
# 纯查询 `wm size`（后面没有参数）不应命中，所以都要求 `[[:space:]]+` 后才判。
for pat in \
    'wm size[[:space:]]+[0-9]' \
    'wm size[[:space:]]+-[0-9]' \
    'wm size[[:space:]]+reset' \
    'BootReceiver' \
    'JobScheduler' \
    'AlarmManager' \
    'RECEIVE_BOOT_COMPLETED'; do
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
    echo "[5/8] FAIL  硬约束命中：$HITS"
    fail "hard-constraints"
elif [ "$AGP" != "8.13.2" ]; then
    echo "[5/8] FAIL  AGP 版本是 ${AGP:-未知}，必须是 8.13.2（9.x 会让 Chaquopy 静默失效）"
    fail "agp-version"
else
    echo "[5/8] OK    无禁用符号，AGP $AGP / $GRADLE_WRAPPER"
fi

# ⑥ 状态同步门禁（规矩见 AGENTS.md：改了 app/ 或 poc/ 必须同时改 PROGRESS.md）
# diff 范围：origin/main...HEAD 优先；没有 origin/main（浅克隆/新仓）退 HEAD~1..HEAD；
# 两者都没有就 SKIP —— 门禁自己绝不允许把 CI 搞红。
if [ "${OAS_SKIP_PROGRESS_CHECK:-0}" = "1" ]; then
    echo "[6/8] SKIP  OAS_SKIP_PROGRESS_CHECK=1"
else
    RANGE=""
    if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
        RANGE="origin/main...HEAD"
    elif git rev-parse --verify -q HEAD~1 >/dev/null 2>&1; then
        RANGE="HEAD~1..HEAD"
    fi
    if [ -z "$RANGE" ]; then
        echo "[6/8] SKIP  无可用 diff"
    else
        CHANGED=$(git diff --name-only "$RANGE" 2>/dev/null)
        if printf '%s\n' "$CHANGED" | grep -qE '^(app|poc)/'; then
            if printf '%s\n' "$CHANGED" | grep -qx 'PROGRESS.md'; then
                echo "[6/8] OK    代码有改动，PROGRESS.md 已同步"
            else
                echo "[6/8] FAIL  改了 app/ 或 poc/ 但没改 PROGRESS.md（规矩见 AGENTS.md）"
                fail "progress-not-updated"
            fi
        else
            echo "[6/8] OK    无 app/ 或 poc/ 改动（不需要更新 PROGRESS.md）"
        fi
    fi
fi

# ⑦ 状态文件行数上限（AGENTS ≤50 / PROGRESS ≤40，见 DECISIONS.md D-015）
# 状态卡一旦超长就会稀释最早设定，所以门禁直接卡行数：超了就删，不是缩。
if [ "${OAS_SKIP_STATE_CHECK:-0}" = "1" ]; then
    echo "[7/8] SKIP  OAS_SKIP_STATE_CHECK=1"
else
    MISSING=""
    for sf in AGENTS.md PROGRESS.md DECISIONS.md; do
        [ -f "$sf" ] || MISSING="$MISSING $sf"
    done
    if [ -n "$MISSING" ]; then
        echo "[7/8] FAIL  缺状态文件$MISSING"
        fail "state-files-missing"
    else
        AGENTS_LINES=$(wc -l <AGENTS.md | tr -d ' ')
        PROGRESS_LINES=$(wc -l <PROGRESS.md | tr -d ' ')
        DEC_COUNT=$(grep -c '^| D-' DECISIONS.md 2>/dev/null || true)
        DEC_COUNT=${DEC_COUNT:-0}
        if [ "$AGENTS_LINES" -gt 50 ]; then
            echo "[7/8] FAIL  AGENTS.md $AGENTS_LINES 行 > 50（超了就删，不是缩；见 D-015）"
            fail "agents-too-long"
        elif [ "$PROGRESS_LINES" -gt 40 ]; then
            echo "[7/8] FAIL  PROGRESS.md $PROGRESS_LINES 行 > 40（超了就删，不是缩；见 D-015）"
            fail "progress-too-long"
        else
            echo "[7/8] OK    AGENTS $AGENTS_LINES/50 · PROGRESS $PROGRESS_LINES/40 · DECISIONS $DEC_COUNT 条（格式：行数超限就删，不是缩；见 D-015）"
        fi
    fi
fi

# ⑧ PROGRESS.md 不许写死过期 PR 号（D-017）
# 判据：PROGRESS.md 里出现的每个 `#<数字>` 必须能在 `git log --oneline -100` 里找到；
# 一个号都没写 → OK；git 不可用/不在仓库内 → SKIP（门禁自己绝不把 CI 搞红）。
if [ "${OAS_SKIP_STATE_PR_CHECK:-}" = "1" ]; then
    echo "[8/8] SKIP  OAS_SKIP_STATE_PR_CHECK=1"
else
    if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
        LOG=$(git log --oneline -100 2>/dev/null)
        STALE=""
        # 用边界正则避免 #4 误匹配 #40（否则过期号会被当成存在 → 假 OK）
        for n in $(grep -oE '#[0-9]+' PROGRESS.md 2>/dev/null | tr -d '#' | sort -u); do
            printf '%s' "$LOG" | grep -qE "#$n([^0-9]|$)" || STALE="$STALE #$n"
        done
        if [ -n "$STALE" ]; then
            echo "[8/8] FAIL  PROGRESS 写了 git log 里找不到的 PR 号：$STALE"
            fail "state-pr-stale"
        else
            echo "[8/8] OK    PROGRESS 未写死过期 PR 号"
        fi
    else
        echo "[8/8] SKIP  不在 git 仓库内"
    fi
fi

# ---------------------------------------------------------------- #
echo "---"
if [ -n "$FAILED" ]; then
    echo "GATE: FAIL $FAILED"
    exit 1
fi
echo "GATE: PASS"
exit 0
