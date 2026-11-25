#!/usr/bin/env bash
# OAS 一键门禁（对应 docs/WORKFLOW.md §4）
#
#   bash tools/gate.sh            # 本地三项：①daemon 重编(按需) ②assembleDebug ⑤硬约束
#   bash tools/gate.sh --device   # 额外再跑 ③cv_selftest ④四段保活（要装机 + root）
#   bash tools/gate.sh --static   # 云端 CI 用：跳过①②③④（没有 Android SDK），
#                                 # 只跑 bash -n tools/*.sh + ⑤硬约束/AGP
#
# 末行固定输出 `GATE: PASS` 或 `GATE: FAIL <项名>`，便于直接当提交前的判据。
# 每项固定打印一行 `[n/12] OK|SKIP|FAIL ...`。
# ⑥ 状态同步：改了 app/ 或 poc/ 必须同时改 PROGRESS.md（规矩见 AGENTS.md）。
# ⑦ 状态文件上限：AGENTS ≤50 行且 ≤5 KB / PROGRESS ≤40 行且 ≤4 KB（见 DECISIONS.md D-015、D-019）。
# ⑧ PROGRESS.md 不许写死过期 PR 号：文中每个 `PR #<数字>` 必须能在 `git log --all --oneline` 里找到（见 D-017）。
# ⑨ 引用存在性：README.md / docs/**/*.md 里反引号路径必须存在（豁免见脚本内注释，BG-38）。
# ⑩ DECISIONS 单条字节：存量超 300 B 必须标「历史超标」；新增超限由 ⑦ 判（BG-37/BG-38）。
# ⑪ 自述数字比对：交接包行数 / BACKLOG 条目计数 / APK 字节（BG-38）。
# ⑫ 交接包唯一性：两类制各 ≤1 份，已取消类别出现即 FAIL（BG-38 定稿 + BG-39）。
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
    echo "[1/12] SKIP  --static（云端无 Android SDK）"
else
    if [ -f "$STAMP" ] && [ "$(cat "$STAMP" 2>/dev/null)" = "$JAVA_SUM" ] && [ -f "$JAR" ]; then
        echo "[1/12] SKIP  java 未变化（server.jar $(stat -c%s "$JAR" 2>/dev/null) B）"
    else
        if bash tools/build_displayserver.sh >poc/displayserver/build/gate-build.log 2>&1; then
            printf '%s\n' "$JAVA_SUM" >"$STAMP"
            echo "[1/12] OK    server.jar $(stat -c%s "$JAR" 2>/dev/null) B"
        else
            echo "[1/12] FAIL  build_displayserver（见 poc/displayserver/build/gate-build.log）"
            fail "daemon-build"
        fi
    fi
fi

# ---------------------------------------------------------------- #
# ② assembleDebug（必须用 ./gradlew；PATH 上的 gradle 4.31 会报 Task not found）
# ---------------------------------------------------------------- #
if [ "$STATIC" = "1" ]; then
    echo "[2/12] SKIP  --static（云端无 Android SDK）"
elif ./gradlew :app:assembleDebug >build/gate-gradle.log 2>&1; then
    if [ -f "$APK" ]; then
        echo "[2/12] OK    APK md5 $(md5sum "$APK" | cut -d' ' -f1) / $(stat -c%s "$APK") B"
    else
        echo "[2/12] FAIL  BUILD 通过但找不到 $APK"
        fail "apk-missing"
    fi
else
    echo "[2/12] FAIL  assembleDebug（见 build/gate-gradle.log）"
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
        echo "[3/12] OK    cv_selftest ${PASSED:-0}/${TOTAL:-0}"
    else
        echo "[3/12] FAIL  cv_selftest ${PASSED:-0}/${TOTAL:-0}（见 $CACHE_DEVICE/cv_selftest.txt）"
        fail "cv_selftest"
    fi
else
    echo "[3/12] SKIP  未加 --device"
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
        echo "[4/12] FAIL  四段报告 ${KEEPALIVE_TIMEOUT}s 内未定稿（${SCORE:-无报告}，末态 $WAITED s）"
        fail "keepalive-timeout"
    elif [ "$SCORE" = "通过4/4" ] && [ -n "${HOLE:-}" ] && [ "${HOLE:-0}" -lt 5000 ]; then
        echo "[4/12] OK    四段 4/4，C 段最大空洞 ${HOLE}ms"
    else
        echo "[4/12] FAIL  四段 ${SCORE:-无报告}，C 段最大空洞 ${HOLE:-n/a}ms"
        fail "keepalive-4of4"
    fi
    root_shell "am force-stop $PKG"
else
    echo "[4/12] SKIP  未加 --device"
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
    echo "[5/12] FAIL  硬约束命中：$HITS"
    fail "hard-constraints"
elif [ "$AGP" != "8.13.2" ]; then
    echo "[5/12] FAIL  AGP 版本是 ${AGP:-未知}，必须是 8.13.2（9.x 会让 Chaquopy 静默失效）"
    fail "agp-version"
else
    echo "[5/12] OK    无禁用符号，AGP $AGP / $GRADLE_WRAPPER"
fi

# ⑥ 状态同步门禁（规矩见 AGENTS.md：改了 app/ 或 poc/ 必须同时改 PROGRESS.md）
# diff 范围：origin/main...HEAD 优先；没有 origin/main（浅克隆/新仓）退 HEAD~1..HEAD；
# 两者都没有就 SKIP —— 门禁自己绝不允许把 CI 搞红。
if [ "${OAS_SKIP_PROGRESS_CHECK:-0}" = "1" ]; then
    echo "[6/12] SKIP  OAS_SKIP_PROGRESS_CHECK=1"
else
    RANGE=""
    if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
        RANGE="origin/main...HEAD"
    elif git rev-parse --verify -q HEAD~1 >/dev/null 2>&1; then
        RANGE="HEAD~1..HEAD"
    fi
    if [ -z "$RANGE" ]; then
        echo "[6/12] SKIP  无可用 diff"
    else
        CHANGED=$(git diff --name-only "$RANGE" 2>/dev/null)
        if printf '%s\n' "$CHANGED" | grep -qE '^(app|poc)/'; then
            if printf '%s\n' "$CHANGED" | grep -qx 'PROGRESS.md'; then
                echo "[6/12] OK    代码有改动，PROGRESS.md 已同步"
            else
                echo "[6/12] FAIL  改了 app/ 或 poc/ 但没改 PROGRESS.md（规矩见 AGENTS.md）"
                fail "progress-not-updated"
            fi
        else
            echo "[6/12] OK    无 app/ 或 poc/ 改动（不需要更新 PROGRESS.md）"
        fi
    fi
fi

# ⑦ 状态文件行数上限（AGENTS ≤50 / PROGRESS ≤40，见 DECISIONS.md D-015）
# 状态卡一旦超长就会稀释最早设定，所以门禁直接卡行数：超了就删，不是缩。
if [ "${OAS_SKIP_STATE_CHECK:-0}" = "1" ]; then
    echo "[7/12] SKIP  OAS_SKIP_STATE_CHECK=1"
else
    MISSING=""
    for sf in AGENTS.md PROGRESS.md DECISIONS.md; do
        [ -f "$sf" ] || MISSING="$MISSING $sf"
    done
    if [ -n "$MISSING" ]; then
        echo "[7/12] FAIL  缺状态文件$MISSING"
        fail "state-files-missing"
    else
        AGENTS_LINES=$(wc -l <AGENTS.md | tr -d ' ')
        PROGRESS_LINES=$(wc -l <PROGRESS.md | tr -d ' ')
        AGENTS_BYTES=$(wc -c <AGENTS.md | tr -d ' ')
        PROGRESS_BYTES=$(wc -c <PROGRESS.md | tr -d ' ')
        DEC_COUNT=$(grep -c '^| D-' DECISIONS.md 2>/dev/null || true)
        DEC_COUNT=${DEC_COUNT:-0}
        # BG-37：D-031 之后的「新增条目」单条 ≤300 B（存量已按 D-031 标「历史超标」豁免）
        NEWBAD=$(LC_ALL=C awk -F'|' '
            /^\| *D-[0-9]+/ {
                n=$2; gsub(/[^0-9]/, "", n); n=n+0;
                if (n > 31 && length($0) > 300) printf "D-%03d(%dB) ", n, length($0)
            }' DECISIONS.md 2>/dev/null)
        if [ "$AGENTS_BYTES" -gt 5120 ]; then
            echo "[7/12] FAIL  AGENTS.md $AGENTS_BYTES B > 5120 B（行数没超但字节超了；见 D-019）"
            fail "agents-too-big"
        elif [ "$PROGRESS_BYTES" -gt 4096 ]; then
            echo "[7/12] FAIL  PROGRESS.md $PROGRESS_BYTES B > 4096 B（见 D-019）"
            fail "progress-too-big"
        elif [ "$AGENTS_LINES" -gt 50 ]; then
            echo "[7/12] FAIL  AGENTS.md $AGENTS_LINES 行 > 50（超了就删，不是缩；见 D-015）"
            fail "agents-too-long"
        elif [ "$PROGRESS_LINES" -gt 40 ]; then
            echo "[7/12] FAIL  PROGRESS.md $PROGRESS_LINES 行 > 40（超了就删，不是缩；见 D-015）"
            fail "progress-too-long"
        elif [ -n "$NEWBAD" ]; then
            echo "[7/12] FAIL  DECISIONS 新增条目超 300 B：$NEWBAD（D-031/BG-37）"
            fail "decisions-entry-too-big"
        else
            echo "[7/12] OK    AGENTS ${AGENTS_LINES}/50行·${AGENTS_BYTES}/5120B · PROGRESS ${PROGRESS_LINES}/40行·${PROGRESS_BYTES}/4096B · DECISIONS ${DEC_COUNT} 条"
        fi
    fi
fi

# ⑧ PROGRESS.md 不许写死过期 PR 号（D-017）
# 判据：PROGRESS.md 里出现的每个 `PR #<数字>` 必须能在 `git log --all --oneline` 里找到；
# 一个号都没写 → OK；git 不可用 / 不在仓库内 / 浅克隆 → SKIP（门禁自己绝不把 CI 搞红）。
if [ "${OAS_SKIP_STATE_PR_CHECK:-}" = "1" ]; then
    echo "[8/12] SKIP  OAS_SKIP_STATE_PR_CHECK=1"
else
    if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
        # 浅克隆没有历史可查，「查不到」不等于「号过期」→ 宁可 SKIP 也不误报
        if [ "$(git rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
            echo "[8/12] SKIP  浅克隆仓库（无历史可查）"
        else
            LOG=$(git log --all --oneline 2>/dev/null)
            STALE=""
            # 抓取端要求 `PR #<数字>` 前缀，避免把条目编号、代码块里的 #123 当 PR 号；
            # 匹配端用边界正则避免 #4 误匹配 #40（否则过期号会被当成存在 → 假 OK）
            for n in $(grep -oE 'PR *#[0-9]+' PROGRESS.md 2>/dev/null | grep -oE '[0-9]+' | sort -u); do
                printf '%s' "$LOG" | grep -qE "#$n([^0-9]|$)" || STALE="$STALE #$n"
            done
            if [ -n "$STALE" ]; then
                echo "[8/12] FAIL  PROGRESS 写了 git log 里找不到的 PR 号：$STALE"
                fail "state-pr-stale"
            else
                echo "[8/12] OK    PROGRESS 未写死过期 PR 号"
            fi
        fi
    else
        echo "[8/12] SKIP  不在 git 仓库内"
    fi
fi

# ⑨ 引用存在性（BG-38）：README.md 与 docs/**/*.md 里反引号包住的「仓库内路径」必须存在
# 豁免（BG-38 定稿）：docs/HANDOFF.md（历史台账）、docs/handoff/*.md（历史包本身）；
# 同行出现「已删/已被取代/已作废/未入库/跟丢」；含 `*` 或以 `/` 结尾；含 YYYYMMDD 的模板占位。
# 实现收窄（必须如此，否则豁免清单只会越加越长）：只校验带**已知顶级目录前缀**
# （app/ docs/ poc/ tools/ vendor/ patches/ gradle/ dist/ build/）**且带扩展名**的 token。
# 裸文件名（`Device.kt`）、运行时文件名（`filesDir/oas/cache/bench.json`、`xxx.apk`）、
# 绝对路径、含空格/占位符的 token 一律不校验 —— 它们不是「仓库内的文件引用」。
if [ "${OAS_SKIP_REF_CHECK:-}" = "1" ]; then
    echo "[9/12] SKIP  OAS_SKIP_REF_CHECK=1"
elif ! command -v git >/dev/null 2>&1 || ! git rev-parse --git-dir >/dev/null 2>&1; then
    echo "[9/12] SKIP  不在 git 仓库内"
else
    REF_FILES=$(git ls-files 'README.md' 'docs/*.md' 2>/dev/null |
        grep -v '^docs/HANDOFF\.md$' | grep -v '^docs/handoff/')
    # 说明：只扫已入库文件（未 add 的新文件要等 git add 后才入检；CI/pre-push 阶段均已入库）
    REFS=$(LC_ALL=C awk '
        /已删|已被取代|已作废|未入库|跟丢/ { next }
        {
            line=$0
            while (match(line, /`[^`]+`/)) {
                t=substr(line, RSTART+1, RLENGTH-2)
                line=substr(line, RSTART+RLENGTH)
                if (t ~ /[ \t]/) continue
                if (t ~ /^https?:/) continue
                if (t ~ /\*/) continue
                if (t ~ /\/$/) continue
                if (t ~ /YYYYMMDD/) continue
                if (t ~ /^\//) continue
                if (t !~ /^(app|docs|poc|tools|vendor|patches|gradle|dist|build)\//) continue
                if (t !~ /\.(md|sh|py|kt|kts|java|jar|apk|json|txt|yml|yaml|toml|properties|patch|gradle)$/) continue
                printf "%s\t%s\n", FILENAME, t
            }
        }' $REF_FILES 2>/dev/null)
    : > build/gate-dead.txt
    while IFS="	" read -r rf t; do
        [ -n "$t" ] || continue
        if [ -e "$t" ] || [ -e "$(dirname "$rf")/$t" ]; then continue; fi
        grep -qxF "$t" build/gate-dead.txt 2>/dev/null || echo "$t" >> build/gate-dead.txt
    done <<EOF
$REFS
EOF
    DEADN=$(wc -l < build/gate-dead.txt | tr -d ' ')
    REFN=$(printf '%s' "$REFS" | grep -c . || true)
    if [ "${DEADN:-0}" -gt 0 ]; then
        echo "[9/12] FAIL  引用文件不存在：$(head -3 build/gate-dead.txt | tr '\n' ' ')等 $DEADN 个（明细 build/gate-dead.txt）"
        fail "ref-missing"
    else
        echo "[9/12] OK    文档内引用路径均存在（已核 ${REFN:-0} 处）"
    fi
fi

# ⑩ DECISIONS 单条字节（BG-38 + D-031）：存量（≤D-031）超 300 B 必须带「历史超标」标注；
# 新增条目超限归 ⑦ 判（BG-37），这里只核存量标注是否齐。
if [ "${OAS_SKIP_DEC_CHECK:-}" = "1" ]; then
    echo "[10/12] SKIP  OAS_SKIP_DEC_CHECK=1"
elif [ ! -f DECISIONS.md ]; then
    echo "[10/12] SKIP  无 DECISIONS.md"
else
    DECSTAT=$(LC_ALL=C awk -F'|' '
        /^\| *D-[0-9]+/ {
            n=$2; gsub(/[^0-9]/, "", n); n=n+0;
            if (length($0) > 300) {
                over++
                if (n <= 31) { legacy++; if ($0 !~ /历史超标/) unmarked = unmarked sprintf("D-%03d(%dB) ", n, length($0)) }
            }
        }
        END { printf "%d %d %s", over+0, legacy+0, unmarked }' DECISIONS.md)
    OVER=$(printf '%s' "$DECSTAT" | cut -d' ' -f1)
    LEGACY=$(printf '%s' "$DECSTAT" | cut -d' ' -f2)
    UNMARK=$(printf '%s' "$DECSTAT" | cut -d' ' -f3-)
    if [ -n "$UNMARK" ]; then
        echo "[10/12] FAIL  存量超限条目未标「历史超标」：$UNMARK"
        fail "decisions-legacy-unmarked"
    else
        echo "[10/12] OK    单条超 300B 共 ${OVER} 条（存量 ${LEGACY} 条均已标「历史超标」）"
    fi
fi

# ⑪ 自述数字比对（BG-38）：只查三类（交接包行数 / BACKLOG 条目计数 / APK 字节），其余不查；
# 查不到的类别一律跳过（门禁自己绝不把 CI 搞红）。
if [ "${OAS_SKIP_NUM_CHECK:-}" = "1" ]; then
    echo "[11/12] SKIP  OAS_SKIP_NUM_CHECK=1"
else
    : > build/gate-nums.txt
    # ① `<docs/handoff/某包>.md` 与「N 行」同行 → 与实际 wc -l 比对；引用的包不存在则跳过
    grep -rn 'docs/handoff/' docs/*.md docs/handoff/*.md 2>/dev/null | grep '行' > build/gate-num-src.txt 2>/dev/null
    if [ -s build/gate-num-src.txt ]; then
        while IFS= read -r ln; do
            pkgs=$(printf '%s' "$ln" | grep -oE 'docs/handoff/[0-9]{8}-[0-9]{4}-[^`"、 ）]+\.md' | sort -u)
            nums=$(printf '%s' "$ln" | grep -oE '[0-9]+ 行' | grep -oE '[0-9]+' | sort -u)
            [ -n "$pkgs" ] && [ -n "$nums" ] || continue
            for p in $pkgs; do
                [ -f "$p" ] || continue
                real=$(wc -l < "$p" | tr -d ' ')
                for n in $nums; do
                    [ "$n" = "$real" ] || echo "$p: 文中 $n 行 / 实际 $real 行" >> build/gate-nums.txt
                done
            done
        done < build/gate-num-src.txt
    fi
    # ② `[x] N 项 / [ ] M 项` vs BACKLOG 实际计数
    if [ -f docs/BACKLOG.md ]; then
        CLD=$(grep -c '^- \[x\]' docs/BACKLOG.md 2>/dev/null || true)
        OPD=$(grep -c '^- \[ \]' docs/BACKLOG.md 2>/dev/null || true)
        grep -rnE '\[x\] *[0-9]+ *项|\[ \] *[0-9]+ *项' docs/*.md README.md 2>/dev/null > build/gate-num-bl.txt || true
        if [ -s build/gate-num-bl.txt ]; then
            while IFS= read -r ln; do
                xs=$(printf '%s' "$ln" | grep -oE '\[x\] *[0-9]+' | grep -oE '[0-9]+' | head -1)
                os=$(printf '%s' "$ln" | grep -oE '\[ \] *[0-9]+' | grep -oE '[0-9]+' | head -1)
                [ -n "$xs" ] && [ "$xs" != "${CLD:-0}" ] && echo "BACKLOG [x] 文中 $xs / 实际 ${CLD:-0}" >> build/gate-nums.txt
                [ -n "$os" ] && [ "$os" != "${OPD:-0}" ] && echo "BACKLOG [ ] 文中 $os / 实际 ${OPD:-0}" >> build/gate-nums.txt
            done < build/gate-num-bl.txt
        fi
    fi
    # ③ 只有写到「现役产物路径 app/build/outputs/apk/…」的行才比字节；
    #    `docs/evidence/`（时点证据）、`dist/` 旧包、明说「现查/不要引用/曾为」的行都不参与。
    grep -rnE 'app/build/outputs/apk/[^ ]*\.apk' docs/*.md README.md 2>/dev/null |
        grep -E '[0-9]{1,3}(,[0-9]{3})+ *B' |
        grep -v '^docs/evidence/' |
        grep -vE '现查|不要引用|当时|时点|曾为' > build/gate-num-apk.txt || true
    if [ -s build/gate-num-apk.txt ]; then
        while IFS= read -r ln; do
            b=$(printf '%s' "$ln" | grep -oE '[0-9]{1,3}(,[0-9]{3})+ *B' | head -1 | tr -d ', B')
            apk=$(printf '%s' "$ln" | grep -oE '[A-Za-z0-9_./-]+\.apk' | head -1)
            [ -n "$apk" ] || apk='app/build/outputs/apk/debug/app-debug.apk'
            [ -f "$apk" ] || continue
            real=$(stat -c%s "$apk" 2>/dev/null || echo '')
            [ -n "$real" ] && [ "$b" != "$real" ] && echo "APK 文中 $b B / 实际 $real B（$apk）" >> build/gate-nums.txt
        done < build/gate-num-apk.txt
    fi
    NUMN=$(wc -l < build/gate-nums.txt | tr -d ' ')
    if [ "${NUMN:-0}" -gt 0 ]; then
        echo "[11/12] FAIL  自述数字与实际不符：$(head -2 build/gate-nums.txt | tr '\n' ';')（明细 build/gate-nums.txt）"
        fail "self-reported-numbers"
    else
        echo "[11/12] OK    自述数字与实际一致（无可查项则跳过）"
    fi
fi

# ⑫ 交接包唯一性（BG-38 定稿；按 BG-39 / D-026 后的「两类制」实现）：
# docs/handoff/ 下 `-main-ai` ≤1 份、`-d-reviewer` ≤1 份；已取消类别（`-e<N>-handoff` 交接包位、
# `-BG-<号>-` 写手包）出现即 FAIL。BG-38 原文「四类各 1 份」已被 BG-39 两类制取代。
if [ "${OAS_SKIP_HANDOFF_CHECK:-}" = "1" ]; then
    echo "[12/12] SKIP  OAS_SKIP_HANDOFF_CHECK=1"
elif [ ! -d docs/handoff ]; then
    echo "[12/12] SKIP  无 docs/handoff/"
else
    MAINN=$(find docs/handoff -maxdepth 1 -name '*-main-ai.md' | wc -l | tr -d ' ')
    DN=$(find docs/handoff -maxdepth 1 -name '*-d-reviewer.md' | wc -l | tr -d ' ')
    OBS=$(find docs/handoff -maxdepth 1 \( -name '*-e[0-9]*-handoff.md' -o -name '*-BG-[0-9]*.md' \) | tr '\n' ' ')
    if [ "$MAINN" -gt 1 ] || [ "$DN" -gt 1 ] || [ -n "$OBS" ]; then
        echo "[12/12] FAIL  交接包超份或含已取消类别：main-ai=$MAINN d-reviewer=$DN 已取消=$OBS"
        fail "handoff-dup"
    else
        echo "[12/12] OK    交接包 main-ai=$MAINN 份 / d-reviewer=$DN 份，无已取消类别"
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
