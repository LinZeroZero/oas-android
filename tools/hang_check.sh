#!/system/bin/sh
# OAS 长时挂机一键检测（BG-32）
#
# 用法（必须在 root shell 里跑：su -c / super_admin:shell）：
#   sh tools/hang_check.sh start    # 开始采样（默认 5s 一拍），打印会话目录 / 虚拟屏 id / 采样器 pid
#   sh tools/hang_check.sh status   # 瞄一眼当前会话的最后一行样本
#   sh tools/hang_check.sh stop     # 停采样 → 出报告 + PASS/FAIL 判定（同时落盘 report.txt）
#   sh tools/hang_check.sh help     # 这份说明
#
# 环境变量（都可选）：
#   OAS_INTERVAL=5          采样间隔（秒）
#   OAS_HANG_DIR=<dir>      会话目录；不设则 /data/local/tmp/oas-hang/<时间戳>
#   OAS_DISPLAY_ID=<id>     虚拟屏 id；不设则从 dumpsys display 的 DisplayInfo{"oas"…displayId N} 推
#   OAS_FRAMES_EVERY=5      frames 走 app 通道时每 N 拍取一次
#   OAS_SERVER_JAR=<jar>    daemon --cmd 用的 server.jar（默认 app files 目录里那份）
#   OAS_GAME_PKG=<pkg>      游戏包名，默认 com.netease.onmyoji
#   OAS_DAEMON_PID / OAS_GAME_PID   直接钉死 pid（调试用，跳过自动识别）
#
# 只读原则：不改任何系统设置、不增删应用、不注册自启动；只读 /proc、/sys/power/wake_lock 与
# dumpsys。本脚本自己不建虚拟屏、不拉游戏，挂机链条得由 app/daemon 侧起好，这里只负责看。
#
# 每拍 15 列，顺序固定（'|' 分隔）：
#   ts|awake|ndisp|did|dpid|djiff|gpid|gjiff|sfjiff|wl|load1|memfree|frames|dstate|gstate
#   jiff = /proc/<pid>/stat 的 utime+stime（去掉 "pid (comm) " 前缀后的 $12+$13）
#   state = 同上去前缀后的 $1（R/S/D/T/Z；D/T 即疑似被 cgroup freezer 冻住）
set -u

GAME_PKG=${OAS_GAME_PKG:-com.netease.onmyoji}
INTERVAL=${OAS_INTERVAL:-5}
BASE=${OAS_HANG_BASE:-/data/local/tmp/oas-hang}
COLS='ts|awake|ndisp|did|dpid|djiff|gpid|gjiff|sfjiff|wl|load1|memfree|frames|dstate|gstate'

# daemon 通道：app_process + server.jar。
# ⚠ 必须带 --connect：老的 `--cmd` 是在新进程里就地 dispatch（DisplayEngine 是空的），
#   `stats` 永远回 no such display —— 上一轮 819 次 frames 全 n/a 就是这么来的。
SOCKET_NAME=${OAS_SOCKET:-org.onmyoji.oas.display}
SERVER_JAR=${OAS_SERVER_JAR:-/data/user/0/org.onmyoji.oas.android/files/oas/server/server.jar}
APP_PROCESS=${OAS_APP_PROCESS:-/system/bin/app_process}
# frames 走 app 通道时每 N 拍取一次：每拍拉一个 app_process 太吃 CPU，会污染 loadavg
FRAMES_EVERY=${OAS_FRAMES_EVERY:-5}
# 快路：daemon 心跳文件（Heartbeat.java 写，一行一拍），末行新鲜就直接读 frames，省一次 JVM
HB_FILE=${OAS_HB_FILE:-/data/user/0/org.onmyoji.oas.android/files/oas/cache/keepalive_hb.jsonl}
HB_MAX_AGE=${OAS_HB_MAX_AGE:-5}
# 硬超时自停上限（秒，0=不限）：没人值守的长跑必须有尽头（上一轮被拉成 47.8 小时）
MAX_DURATION=${OAS_MAX_DURATION:-900}

case "$INTERVAL" in
    '' | *[!0-9]*) INTERVAL=5 ;;
esac
[ "$INTERVAL" -ge 1 ] || INTERVAL=5
case "$FRAMES_EVERY" in
    '' | *[!0-9]*) FRAMES_EVERY=5 ;;
esac
[ "$FRAMES_EVERY" -ge 1 ] || FRAMES_EVERY=5
case "$MAX_DURATION" in
    '' | *[!0-9]*) MAX_DURATION=900 ;;
esac

SELF=$(readlink -f "$0" 2>/dev/null)
[ -n "${SELF:-}" ] || SELF=$0

die() {
    echo "$*" >&2
    exit 2
}

# ---- 取数小工具 -------------------------------------------------------------

# 去掉 "pid (comm) " 前缀后的剩余字段。comm 里可能带空格/括号，所以按最后一个 ") " 切。
stat_tail() {
    sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null
}

# utime+stime。取空（进程没了 / 权限不足）一律兜底 0，绝不吐空字段。
cpu_jiff() {
    v=$(stat_tail "$1" | awk '{ print $12 + $13 }' 2>/dev/null)
    case "$v" in
        '' | *[!0-9]*) echo 0 ;;
        *) echo "$v" ;;
    esac
}

# 进程状态字母。
proc_state() {
    v=$(stat_tail "$1" | awk '{ print $1 }' 2>/dev/null)
    case "$v" in
        R | S | D | T | Z) echo "$v" ;;
        *) echo 0 ;;
    esac
}

# 常驻 daemon：ps 里同时含 app_process 与 oas.server.Main。
# 为什么写成 app[_]process / oas[.]server[.]Main：本函数自己那条 awk 的命令行里包含这些
# 模式文本，而方括号里的字符**匹配不到自己**（"app[_]process" 这个字样里没有 "app_process"），
# 所以不靠 `$2 != "awk"` 那种侥幸判断也不会自我命中。PID 列再卡一次纯数字。
# 关键：必须排除我们/别人用 `--cmd` 拉起来的**瞬时 JVM**（它们的 cmdline 同样含
# app_process 与 oas.server.Main，被认成 daemon 会让 dpid 记成 JVM、下拍消失 → 假 FAIL）。
daemon_pid() {
    if [ -n "${OAS_DAEMON_PID:-}" ]; then
        echo "$OAS_DAEMON_PID"
        return
    fi
    ps -A -o PID,ARGS 2>/dev/null |
        awk '$1 ~ /^[0-9]+$/ && /app[_]process/ && /oas[.]server[.]Main/ && !/--cm[d]/ { print $1; exit }'
}

pid_or_zero() {
    case "${1:-}" in
        '' | *[!0-9]*) echo 0 ;;
        *) echo "$1" ;;
    esac
}

# pid 身份校验：/proc/<pid>/cmdline（NUL 换成空格）里必须含 $2。
# pid 会被系统复用，光看 /proc/<pid> 存在会把别的进程当成 daemon/游戏/采样器。
# cmdline 为空（僵尸/内核线程）时退回 /proc/<pid>/comm 再试一次。
pid_matches() {
    case "${1:-}" in
        '' | *[!0-9]*) return 1 ;;
    esac
    [ -r "/proc/$1/cmdline" ] || return 1
    c=$(tr '\0' ' ' <"/proc/$1/cmdline" 2>/dev/null)
    if [ -n "$c" ]; then
        case "$c" in
            *"$2"*) return 0 ;;
        esac
        return 1
    fi
    c=$(cat "/proc/$1/comm" 2>/dev/null)
    case "${c:-}" in
        *"$2"*) return 0 ;;
    esac
    return 1
}

# 采样器身份：cmdline 必须同时含脚本名与 _loop 子命令（本脚本自己、且是循环模式）。
pid_is_sampler() {
    case "${1:-}" in
        '' | *[!0-9]*) return 1 ;;
    esac
    [ -r "/proc/$1/cmdline" ] || return 1
    c=$(tr '\0' ' ' <"/proc/$1/cmdline" 2>/dev/null)
    case "$c" in
        *hang_check.sh*) ;;
        *) return 1 ;;
    esac
    case "$c" in
        *_loop*) return 0 ;;
    esac
    return 1
}

# daemon 身份：cmdline 必须同时含 app_process 与 oas.server.Main，
# 且**不是** `--cmd` 拉起的一次性 JVM（那种进程是瞬时的，认成 daemon 会让 dpid 记错）。
pid_is_daemon() {
    pid_matches "$1" app_process || return 1
    pid_matches "$1" oas.server.Main || return 1
    case "$(tr '\0' ' ' <"/proc/$1/cmdline" 2>/dev/null)" in
        *--cm[d]*) return 1 ;;
    esac
    return 0
}

game_pid() {
    if [ -n "${OAS_GAME_PID:-}" ]; then
        echo "$OAS_GAME_PID"
        return
    fi
    pid_or_zero "$(pidof "$GAME_PKG" 2>/dev/null | awk '{ print $1 }')"
}

# ---------------------------------------------------------------------------- #
# 虚拟屏识别（2026-11-15 22:13 真机实测口径）
#
#   DisplayDeviceInfo{"oas", uniqueId="virtual:com.android.shell,0,oas,0", 1280 x 720, density 320}
#   mBaseDisplayInfo=DisplayInfo{"oas", displayId 34, logicalWidth 1280, ...}
#
# 名字就是 **oas**（没有连字符），`grep -c 'oas-'` 恒为 0；displayId 只出现在
# `DisplayInfo{"oas"...}` 行里，`mDisplayId=` 这个写法在虚拟屏上抓不到。
# 所以统一从 DisplayInfo{"oas... 行抽 id，并按 id 去重计数。
# ---------------------------------------------------------------------------- #
VDISP_RE='DisplayInfo\{"oas("|[-_])[^}]*displayId [0-9]+'

vdisp_ids() {
    dumpsys display 2>/dev/null |
        grep -oE "$VDISP_RE" |
        sed -n 's/.*displayId \([0-9][0-9]*\)$/\1/p' |
        sort -u
}

# daemon 通道：app_process 起 server.jar 的 **client 模式**（--connect + --cmd）。
# 为什么不直接用 --cmd：那个模式是本进程 new SocketServer 就地 dispatch，看不到运行态的
# DisplayEngine，`stats` 只会回 no such display。
daemon_cmd() {
    [ -f "$SERVER_JAR" ] || return 1
    [ -x "$APP_PROCESS" ] || return 1
    # app_process 在默认 cwd 起会 SIGSEGV，必须先 cd 到真实存在的目录（evidence 20261115-0057 §0）
    workdir=${OAS_WORKDIR:-/data/local/tmp}
    [ -d "$workdir" ] || workdir=/
    (
        cd "$workdir" 2>/dev/null || cd /
        CLASSPATH="$SERVER_JAR" "$APP_PROCESS" /system/bin org.onmyoji.oas.server.Main \
            --connect "$SOCKET_NAME" --cmd "$1"
    ) 2>/dev/null | head -1
}
# 兜底：问 daemon 要 displayId 列表（每行一个）。**每拍别调**，拉一次 JVM 很贵。
daemon_disp_ids() {
    printf '%s' "$(daemon_cmd list)" | grep -o '"displayId":[0-9][0-9]*' | cut -d: -f2
}


vdisp_id() {
    if [ -n "${OAS_DISPLAY_ID:-}" ]; then
        echo "$OAS_DISPLAY_ID"
        return
    fi
    ids=$(vdisp_ids)
    if [ -n "$ids" ]; then
        printf '%s\n' "$ids" | head -1
        return
    fi
    # 兜底：daemon list（权威，但要拉一次 app_process，所以只在 dumpsys 抽不到时用）
    daemon_disp_ids | head -1
}

# 快路：直接读 daemon 心跳（Heartbeat.java 写的 JSONL，一行一拍）：
#   {"ts":<epochMs>,"displayId":<id>,"frames":<累计帧>,"pid":<daemon pid>}
# 末行新鲜（≤HB_MAX_AGE 秒）且 displayId 匹配就用它的 frames —— 省掉一次 app_process。
# 返回非 0 表示「这条快路给不出数」，由调用方回落主路。
hb_frames() {
    did=$1
    [ -f "$HB_FILE" ] || return 1
    line=$(tail -1 "$HB_FILE" 2>/dev/null)
    [ -n "$line" ] || return 1
    hts=$(printf '%s' "$line" | sed -n 's/.*"ts":\([0-9][0-9]*\).*/\1/p')
    hdid=$(printf '%s' "$line" | sed -n 's/.*"displayId":\([-0-9][0-9]*\).*/\1/p')
    hfr=$(printf '%s' "$line" | sed -n 's/.*"frames":\([-0-9][0-9]*\).*/\1/p')
    case "$hts" in '' | *[!0-9]*) return 1 ;; esac
    [ "$hdid" = "$did" ] || return 1
    now=$(date +%s)
    age=$((now - hts / 1000))
    [ "$age" -ge 0 ] && [ "$age" -le "$HB_MAX_AGE" ] || return 1
    case "$hfr" in '' | *[!0-9]*) return 1 ;; esac
    echo "$hfr"
}

# 帧数通路：python3 直连抽象 socket（开发机）→ app_process --connect（本机）→ 无。
frames_channel() {
    if [ -n "${OAS_FRAMES_CHANNEL:-}" ]; then
        echo "$OAS_FRAMES_CHANNEL"
        return
    fi
    if command -v python3 >/dev/null 2>&1; then
        echo python
        return
    fi
    if [ -f "$SERVER_JAR" ] && [ -x "$APP_PROCESS" ]; then
        echo app
        return
    fi
    echo none
}

# 取帧数。约定：数字 = 取到；`-` = 本拍被节流跳过（不是取不到）；
# `n/a` = 该环境确实没有可用通路 —— 报告里会点名，绝不当成「没采到所以判据跳过」。
frames_of() {
    did=$1
    tick=$2
    case "$did" in
        '' | - | *[!0-9]*) echo 'n/a'; return ;;
    esac
    # ① 快路：心跳文件新鲜就直接用（一次 tail，比拉 JVM 便宜得多）
    if hb_value=$(hb_frames "$did"); then
        echo "$hb_value"
        return
    fi
    channel=$(frames_channel)
    if [ "$channel" = python ]; then
        python3 - "$did" "$SOCKET_NAME" <<'PY' 2>/dev/null || echo 'n/a'
import re
import socket
import sys

s = socket.socket(socket.AF_UNIX)
s.settimeout(6)
try:
    # 抽象名由调用方传进来（默认 SocketServer.ABSTRACT_NAME = org.onmyoji.oas.display）
    s.connect("\0" + sys.argv[2])
    s.send(("stats %s\n" % sys.argv[1]).encode())
    data = s.recv(65536).decode()
except Exception:
    print("n/a")
    raise SystemExit(0)
m = re.search(r'"frames"\s*:\s*(-?[0-9]+)', data)
print(m.group(1) if m else "n/a")
PY
        return
    fi
    if [ "$channel" = app ]; then
        if [ "$((tick % FRAMES_EVERY))" -ne 0 ]; then
            echo '-'
            return
        fi
        value=$(daemon_cmd "stats $did" | grep -o '"frames":[-0-9][0-9]*' | head -1 | cut -d: -f2)
        case "$value" in
            '' | *[!0-9]*) echo 'n/a' ;;
            *) echo "$value" ;;
        esac
        return
    fi
    echo 'n/a'
}

# ---- 单次采样 ---------------------------------------------------------------

sample_once() {
    csv=$1
    tick=${2:-0}
    ts=$(date +%s 2>/dev/null)
    [ -n "$ts" ] || ts=0

    # 负载/内存先取：后面若走 app_process 兜底会拉起 JVM 抬高本拍负载，取晚了就是「被自己污染」的数
    load1=$(awk '{ print $1 }' /proc/loadavg 2>/dev/null)
    [ -n "$load1" ] || load1='-'
    memfree=$(awk '/^MemFree:/ { print $2 }' /proc/meminfo 2>/dev/null)
    [ -n "$memfree" ] || memfree='-'

    awake=$(dumpsys power 2>/dev/null | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)
    [ -n "$awake" ] || awake='?'

    # 虚拟屏：本拍只 dumpsys 一次，同一份 id 列表同时推出「数量」和「displayId」。
    # dumpsys 抽不到（换 ROM / 改名）才问 daemon，且**只问一次**：结果缓存在
    # CACHED_DID / CACHED_NDISP（'-' 表示问过且没有），后续拍不再重复拉 JVM。
    ids=$(vdisp_ids)
    if [ -n "$ids" ]; then
        ndisp=$(printf '%s\n' "$ids" | grep -c '[0-9]')
        CACHED_DID=$(printf '%s\n' "$ids" | head -1)
        CACHED_NDISP=$ndisp
    else
        if [ -z "${CACHED_DID:-}" ] || [ -z "${CACHED_NDISP:-}" ]; then
            ids_from_daemon=$(daemon_disp_ids)
            CACHED_DID=$(printf '%s\n' "$ids_from_daemon" | head -1)
            [ -n "$CACHED_DID" ] || CACHED_DID='-'
            CACHED_NDISP=$(printf '%s\n' "$ids_from_daemon" | grep -c '[0-9]')
            [ -n "$CACHED_NDISP" ] || CACHED_NDISP=0
        fi
        ndisp=$CACHED_NDISP
    fi
    if [ -n "${OAS_DISPLAY_ID:-}" ]; then
        did=$OAS_DISPLAY_ID
    else
        did=${CACHED_DID:-}
    fi
    [ -n "$did" ] || did='-'

    dpid=$(pid_or_zero "$(daemon_pid)")
    # pid 可能被复用（daemon 重启后旧 pid 变别人的）：cmdline 对不上就当这拍没 daemon，
    # 记 0 → 计入 missd，绝不拿无关进程的 CPU 充数（否则会假 PASS）
    if [ "$dpid" != 0 ] && ! pid_is_daemon "$dpid"; then
        dpid=0
    fi
    gpid=$(game_pid)
    if [ "$gpid" != 0 ] && ! pid_matches "$gpid" "$GAME_PKG"; then
        gpid=0
    fi
    sfpid=$(pid_or_zero "$(pidof surfaceflinger 2>/dev/null | awk '{ print $1 }')")

    dj=0
    gj=0
    sfj=0
    dst=0
    gst=0
    if [ "$dpid" != 0 ]; then
        dj=$(cpu_jiff "$dpid")
        dst=$(proc_state "$dpid")
    fi
    if [ "$gpid" != 0 ]; then
        gj=$(cpu_jiff "$gpid")
        gst=$(proc_state "$gpid")
    fi
    if [ "$sfpid" != 0 ]; then
        sfj=$(cpu_jiff "$sfpid")
    fi

    # wake_lock 的 tag 用空格/换行分隔：压成逗号；再兜一层把 '|' 换成 '/'——
    # 这行的任何字段都不能带 '|'，否则 15 列会整体错位，报告按列号取数就会假 PASS
    wl=$(tr '\n' ' ' </sys/power/wake_lock 2>/dev/null | tr -s ' ' ',' | sed 's/^,//; s/,$//; s/|/\//g')
    [ -n "$wl" ] || wl='-'

    frames=$(frames_of "$did" "$tick")

    printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
        "$ts" "$awake" "$ndisp" "$did" "$dpid" "$dj" "$gpid" "$gj" \
        "$sfj" "$wl" "$load1" "$memfree" "$frames" "$dst" "$gst" >>"$csv"
}

# ---- 会话目录 ---------------------------------------------------------------

# status/stop 必须认 OAS_HANG_DIR，不能永远找"最新目录"。
resolve_dir() {
    if [ -n "${OAS_HANG_DIR:-}" ]; then
        if [ -f "$OAS_HANG_DIR/samples.csv" ]; then
            echo "$OAS_HANG_DIR"
            return 0
        fi
        return 1
    fi
    d=$(ls -dt "$BASE"/* 2>/dev/null | head -1)
    if [ -n "$d" ] && [ -f "$d/samples.csv" ]; then
        echo "$d"
        return 0
    fi
    return 1
}

# ---- 子命令 -----------------------------------------------------------------

do_start() {
    out=${OAS_HANG_DIR:-$BASE/$(date +%Y%m%d-%H%M%S)}
    csv=$out/samples.csv

    if [ -f "$out/sampler.pid" ]; then
        oldpid=$(cat "$out/sampler.pid" 2>/dev/null)
        # 只看 /proc/<pid> 存在会把「pid 已被复用」误报成「已有采样器在跑」，必须验身份
        if pid_is_sampler "$oldpid"; then
            die "该目录已有采样器在跑（pid $oldpid）：先 stop，或换一个 OAS_HANG_DIR"
        fi
    fi

    mkdir -p "$out" || die "建目录失败: $out"
    printf '%s\n' "$COLS" >"$csv"
    # 上一轮遗留的 STOP / timeout 会让新采样器一起来就退出、或让报告误报「被自停」
    rm -f "$out/STOP" "$out/report.txt" "$out/timeout"
    printf '%s\n' "$MAX_DURATION" >"$out/limit"

    did=$(vdisp_id)
    [ -n "$did" ] || did='-'

    nohup sh "$SELF" _loop "$out" >"$out/sampler.log" 2>&1 &
    pid=$!
    printf '%s\n' "$pid" >"$out/sampler.pid"

    echo "输出目录  : $out"
    echo "虚拟屏 id : $did"
    echo "采样间隔  : ${INTERVAL}s"
    echo "采样器 pid: $pid"
    echo "表头      : $COLS"
    echo "自停上限  : ${MAX_DURATION}s（0=不限；到点采样器自行收尾并出报告）"
    channel=$(frames_channel)
    case "$channel" in
        app)
            echo "帧数采集  : 快路读心跳（$HB_FILE，≤${HB_MAX_AGE}s）→ 回落 app 通道"
            echo "            app 通道 = server.jar --connect $SOCKET_NAME --cmd stats <id>，每 ${FRAMES_EVERY} 拍取一次"
            ;;
        python)
            echo "帧数采集  : 快路读心跳 → 回落 python3 直连抽象 socket（$SOCKET_NAME）"
            ;;
        *)
            echo "帧数采集  : 无可用通路（既没 python3 也没有 server.jar/app_process）→ frames 记 n/a"
            ;;
    esac
    if [ "$did" = '-' ]; then
        echo "注意      : 当前没抓到虚拟屏 id，等建屏后会自动跟上"
    fi
    echo
    echo "接下来："
    echo "  1) 让 app 侧把挂机链条起好（本脚本不建屏、不拉游戏），然后锁屏开始挂机"
    echo "  2) 中途看状态： sh $SELF status"
    echo "  3) 结束时出报告： sh $SELF stop"
}

do_loop() {
    out=$1
    csv=$out/samples.csv
    tick=0
    started=$(date +%s)
    while [ ! -f "$out/STOP" ]; do
        sample_once "$csv" "$tick"
        tick=$((tick + 1))
        # 硬超时自停：无人值守的长跑必须有尽头（上一轮被「等待被中断」拉成 47.8 小时）
        if [ "$MAX_DURATION" -gt 0 ]; then
            now=$(date +%s)
            if [ $((now - started)) -ge "$MAX_DURATION" ]; then
                : >"$out/STOP"
                printf '%s\n' "$MAX_DURATION" >"$out/timeout"
                echo "$(date '+%F %T') 自停：已到上限 ${MAX_DURATION}s，收尾出报告"
                # 直接复用 stop：它会落 STOP、杀采样器（就是本进程）、然后出报告落 report.txt
                OAS_HANG_DIR="$out" sh "$SELF" stop
                break
            fi
        fi
        i=0
        while [ "$i" -lt "$INTERVAL" ]; do
            [ -f "$out/STOP" ] && break
            sleep 1
            i=$((i + 1))
        done
    done
}

do_status() {
    out=$(resolve_dir) || die "没有可看的会话（OAS_HANG_DIR=${OAS_HANG_DIR:-未设}）。先跑: sh $SELF start"
    csv=$out/samples.csv
    pid=$(pid_or_zero "$(cat "$out/sampler.pid" 2>/dev/null)")
    echo "会话      : $out"
    if pid_is_sampler "$pid"; then
        echo "采样器    : 运行中（pid $pid，间隔 ${INTERVAL}s）"
    else
        echo "采样器    : 已停止"
    fi
    echo "样本行数  : $(wc -l <"$csv" 2>/dev/null | awk '{ print $1 }')（含表头）"
    echo "最新样本  :"
    tail -1 "$csv"
    echo "列顺序    : $COLS"
}

do_stop() {
    out=$(resolve_dir) || die "没有可停的会话（OAS_HANG_DIR=${OAS_HANG_DIR:-未设}）"
    csv=$out/samples.csv

    # ① 落 STOP 标记：采样器每拍都会看它（kill 不到时也不会继续写）
    : >"$out/STOP"

    # ② kill 采样器：先 TERM，最多等 5s 再 KILL。
    # sampler.pid 停止后不会自己消失，pid 会被系统复用 —— 必须验 cmdline 身份，
    # 否则重复 stop / 换人接手时会打死无关进程。
    pid=$(pid_or_zero "$(cat "$out/sampler.pid" 2>/dev/null)")
    if [ "$pid" = 0 ]; then
        echo "提示: 没有 sampler.pid（或内容非法），跳过 kill" >&2
    elif ! pid_is_sampler "$pid"; then
        echo "警告: sampler.pid=$pid 不是本脚本的采样器（pid 已被复用？）→ 不 kill，只清 pidfile" >&2
    else
        kill "$pid" 2>/dev/null
        i=0
        while [ "$i" -lt 5 ]; do
            pid_is_sampler "$pid" || break
            sleep 1
            i=$((i + 1))
        done
        if pid_is_sampler "$pid"; then
            kill -9 "$pid" 2>/dev/null
            sleep 1
        fi
    fi
    # 无论杀没杀成，pidfile 都清掉：保证重复 stop 幂等，也不给下一轮 start 留陈旧 pid
    rm -f "$out/sampler.pid"

    # 出报告
    # 自停标记：采样器到点把自己停掉时会落 $out/timeout（下一节 do_loop）
    selftimed='否'
    if [ -f "$out/timeout" ]; then
        lim=$(cat "$out/limit" 2>/dev/null)
        [ -n "$lim" ] || lim='?'
        selftimed="是（上限 ${lim}s，到点自行收尾）"
    fi
    report=$(awk -F'|' -v OUT="$out" -v FRAMES_CH="$(frames_channel)" -v FRAMES_EVERY="$FRAMES_EVERY" -v SELFTIMED="$selftimed" -v HB_FILE_TXT="$HB_FILE" -v HB_MAX_AGE="$HB_MAX_AGE" '
    NR == 1 { next }
    {
        # 列错位自检：正常是 15 列。少/多列说明有字段混进了分隔符竖线，
        # 按列号取数会整体串位（可能假 PASS），所以这种行直接剔出统计并判 FAIL。
        # 注意：awk 程序体是 shell 单引号串，注释里绝不能出现 ASCII 单引号。
        if (NF != 15) { badcols++; next }
        n++
        t = $1 + 0
        if (n == 1) {
            t0 = t
            dpid0 = $5
            gpid0 = $7
            dj0 = $6 + 0
            gj0 = $8 + 0
            sf0 = $9 + 0
            did = $4
        }
        t1 = t
        if (prev_set) {
            gap = t - prev
            if (gap > maxgap) maxgap = gap
        }
        prev = t
        prev_set = 1
        if (($5 + 0) == 0) missd++
        if (($7 + 0) == 0) missg++
        if ($2 != "Awake") offn++
        nd = $3 + 0
        wl = $10
        ld = $11
        mf = $12
        f_last = $13
        if ($13 == "n/a") fna++
        else if ($13 == "-") fskip++
        if ($13 ~ /^[0-9]+$/) {
            # 帧数走节流通道，末拍常见 '-'/'n/a'，所以首末都按「最近一次真正取到的数」算，
            # 否则有数据也会被误判成「未采到」
            fcnt++
            if (fcnt == 1) f_first = $13
            f_lastnum = $13
        }
        sf_last = $9 + 0
        dpid_last = $5
        gpid_last = $7
        dj_last = $6 + 0
        gj_last = $8 + 0
        dst_last = $14
        gst_last = $15
        if ($14 ~ /^[DT]$/) fzd++
        if ($15 ~ /^[DT]$/) fzg++
    }
    END {
        if (n == 0) {
            printf "报告: samples.csv 只有表头，没采到样本\n"
            if (badcols > 0) printf "CSV 列完整性  : 列错位 %d 行（字段数≠15）\n", badcols
            printf "判定: 样本太短（0 个样本）\n"
            exit 0
        }
        dur = t1 - t0
        mins = int(dur / 60)
        secs = dur % 60
        if (badcols > 0) {
            coltxt = "列错位 " badcols " 行（字段数≠15，已剔出统计）"
        } else {
            coltxt = "OK（每行都是 15 列）"
        }

        dd = 0
        gd = 0
        if ((dpid0 + 0) > 0) dd = dj_last - dj0
        if ((gpid0 + 0) > 0) gd = gj_last - gj0
        sfd = sf_last - sf0
        offp = 0
        if (n > 0) offp = int(offn * 100 / n)

        havef = 0
        if (fcnt >= 2) havef = 1
        if (havef) {
            fdelta = (f_lastnum + 0) - (f_first + 0)
            frtxt = f_first " → " f_lastnum "（+" fdelta "）"
        } else {
            frtxt = "未采到（原因见下一行「帧数通路」；其它判据照常跑，不静默跳过）"
        }
        if (FRAMES_CH == "none") {
            chantxt = "快路=心跳文件（" HB_FILE_TXT "）；无回落通道（无 python3 且缺 server.jar/app_process）→ 取不到即 n/a"
        } else if (FRAMES_CH == "app") {
            chantxt = "快路=心跳文件（" HB_FILE_TXT "，≤" HB_MAX_AGE "s）；回落 app 通道 server.jar --connect --cmd stats（每 " FRAMES_EVERY " 拍一次）"
        } else if (FRAMES_CH == "python") {
            chantxt = "快路=心跳文件；回落 python3 直连抽象 socket"
        } else {
            chantxt = FRAMES_CH
        }

        printf "会话目录      : %s\n", OUT
        printf "样本数        : %d\n", n
        printf "时长          : %ds（%d 分 %d 秒）\n", dur, mins, secs
        printf "自停          : %s\n", SELFTIMED
        printf "采样最大间隔  : %ds（设定以外的空洞都在这里）\n", maxgap + 0
        printf "虚拟屏数量    : %d（末次）｜displayId=%s\n", nd + 0, did
        printf "非 Awake 占比 : %d/%d（%d%%）\n", offn + 0, n, offp
        printf "wakelock      : %s（末次）\n", wl
        printf "loadavg1      : %s（末次）\n", ld
        printf "MemFree       : %s kB（末次）\n", mf
        printf "daemon        : 起始 pid %s → 末次 pid %s｜CPU +%d jiffies\n", dpid0, dpid_last, dd
        printf "游戏          : 起始 pid %s → 末次 pid %s｜CPU +%d jiffies\n", gpid0, gpid_last, gd
        printf "surfaceflinger: CPU +%d jiffies\n", sfd
        printf "末次进程状态  : daemon=%s｜游戏=%s（R/S 正常，D/T 疑似被冻）\n", dst_last, gst_last
        printf "疑似冻结样本  : daemon %d/%d｜游戏 %d/%d（D/T 计数）\n", fzd + 0, n, fzg + 0, n
        printf "帧数          : %s\n", frtxt
        printf "帧数通路      : %s｜n/a %d 拍｜节流跳过 %d 拍\n", chantxt, fna + 0, fskip + 0
        printf "CSV 列完整性  : %s\n", coltxt

        verdict = "PASS"
        why = ""
        if (badcols > 0) {
            verdict = "FAIL"
            why = why "CSV 列错位 " badcols " 行（字段数≠15）；"
        }
        if (n < 3 || dur < 60) {
            if (verdict != "FAIL") verdict = "样本太短"
            why = why "样本不足（" n " 个 / " dur "s，至少 3 个且 ≥60s 才判）；"
        } else {
            if (missd > 0) {
                verdict = "FAIL"
                why = why "daemon 缺失 " missd "/" n " 个样本；"
            }
            if (missg > 0) {
                verdict = "FAIL"
                why = why "游戏缺失 " missg "/" n " 个样本；"
            }
            if ((nd + 0) == 0) {
                verdict = "FAIL"
                why = why "虚拟屏数量为 0；"
            }
            if (fzd * 4 >= n) {
                verdict = "FAIL"
                why = why "daemon 疑似被冻 " fzd "/" n " 样本（≥25%）；"
            }
            if (fzg * 4 >= n) {
                verdict = "FAIL"
                why = why "游戏疑似被冻 " fzg "/" n " 样本（≥25%）；"
            }
            if (havef && fdelta <= 0) {
                verdict = "FAIL"
                why = why "帧数未增长（画面停刷）；"
            }
            if (verdict != "FAIL") {
                if (dd + gd <= 0) {
                    verdict = "WARN"
                    why = why "daemon 与游戏 CPU 均无增长，整链可能已停摆；"
                } else {
                    why = why "daemon+游戏 CPU +" (dd + gd) " jiffies；"
                    if (havef) why = why "帧数 +" fdelta "；"
                }
                if (!havef) {
                    why = why "帧数未采到（n/a " fna + 0 "/" n " 拍，通路=" FRAMES_CH "）→「帧数未增长」判据未生效；"
                    if (verdict == "PASS") verdict = "WARN"
                }
            }
        }
        printf "\n判定: %s\n", verdict
        printf "依据: %s\n", why
        printf "CSV : %s/samples.csv\n", OUT
    }' "$csv")
    rc=$?
    printf '%s\n' "$report" | tee "$out/report.txt"
    # ④ 收尾：STOP 只是采样器的停止信号，会话结束后删掉，别让下一轮 start 撞上
    rm -f "$out/STOP"
    if [ "$rc" != 0 ]; then
        die "报告生成失败（awk rc=$rc）"
    fi
    echo
    echo "报告已落盘: $out/report.txt"
}

do_help() {
    sed -n '2,25p' "$0"
}

# ---- 入口 -------------------------------------------------------------------

if [ "$(id -u 2>/dev/null)" != "0" ]; then
    die "需要 root（dumpsys / /proc 才行）：su -c 'sh $0 $*'"
fi

case "${1:-help}" in
    start) do_start ;;
    status | st) do_status ;;
    stop) do_stop ;;
    _loop)
        if [ -z "${2:-}" ]; then
            die "_loop 需要会话目录（内部用）"
        fi
        do_loop "$2"
        ;;
    help | -h | --help | *) do_help ;;
esac
