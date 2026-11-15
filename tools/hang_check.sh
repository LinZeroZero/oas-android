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
#   OAS_DISPLAY_ID=<id>     虚拟屏 id；不设则从 dumpsys display 的 'oas-' 行里取
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

case "$INTERVAL" in
    '' | *[!0-9]*) INTERVAL=5 ;;
esac
[ "$INTERVAL" -ge 1 ] || INTERVAL=5

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
daemon_pid() {
    if [ -n "${OAS_DAEMON_PID:-}" ]; then
        echo "$OAS_DAEMON_PID"
        return
    fi
    ps -A -o PID,ARGS 2>/dev/null |
        awk '$1 ~ /^[0-9]+$/ && /app[_]process/ && /oas[.]server[.]Main/ { print $1; exit }'
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

# daemon 身份：cmdline 必须同时含 app_process 与 oas.server.Main。
pid_is_daemon() {
    pid_matches "$1" app_process || return 1
    pid_matches "$1" oas.server.Main || return 1
    return 0
}

game_pid() {
    if [ -n "${OAS_GAME_PID:-}" ]; then
        echo "$OAS_GAME_PID"
        return
    fi
    pid_or_zero "$(pidof "$GAME_PKG" 2>/dev/null | awk '{ print $1 }')"
}

vdisp_id() {
    if [ -n "${OAS_DISPLAY_ID:-}" ]; then
        echo "$OAS_DISPLAY_ID"
        return
    fi
    dumpsys display 2>/dev/null | grep 'oas-' | head -1 |
        sed -n 's/.*mDisplayId=\([0-9][0-9]*\).*/\1/p' | head -1
}

# 帧数：走抽象 socket 问 daemon 的 `stats <id>`。没有 python3 就记 "-"，
# 绝不让帧数采集失败把整个采样搞挂。注意命令必须以 \n 结尾。
frames_of() {
    case "$1" in
        '' | - | *[!0-9]*) echo "-"; return ;;
    esac
    if ! command -v python3 >/dev/null 2>&1; then
        echo "-"
        return
    fi
    python3 - "$1" <<'PY' 2>/dev/null || echo "-"
import re
import socket
import sys

s = socket.socket(socket.AF_UNIX)
s.settimeout(6)
try:
    s.connect("\0oas.chan")
    s.send(("stats %s\n" % sys.argv[1]).encode())
    data = s.recv(65536).decode()
except Exception:
    print("-")
    raise SystemExit(0)
m = re.search(r'"frames"\s*:\s*(-?[0-9]+)', data)
print(m.group(1) if m else "-")
PY
}

# ---- 单次采样 ---------------------------------------------------------------

sample_once() {
    csv=$1
    ts=$(date +%s 2>/dev/null)
    [ -n "$ts" ] || ts=0

    awake=$(dumpsys power 2>/dev/null | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)
    [ -n "$awake" ] || awake='?'

    ndisp=$(dumpsys display 2>/dev/null | grep -c 'oas-')
    [ -n "$ndisp" ] || ndisp=0

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

    did=$(vdisp_id)
    [ -n "$did" ] || did='-'

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

    load1=$(awk '{ print $1 }' /proc/loadavg 2>/dev/null)
    [ -n "$load1" ] || load1='-'

    memfree=$(awk '/^MemFree:/ { print $2 }' /proc/meminfo 2>/dev/null)
    [ -n "$memfree" ] || memfree='-'

    frames=$(frames_of "$did")

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
    # 上一轮遗留的 STOP 会让新采样器一起来就退出
    rm -f "$out/STOP" "$out/report.txt"

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
    if [ "$did" = '-' ]; then
        echo "帧数采集  : 没有虚拟屏 id，frames 会记 '-'（需 python3 才能问 daemon）"
    else
        echo "帧数采集  : 目标 displayId=$did（需 python3 才能问 daemon）"
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
    while [ ! -f "$out/STOP" ]; do
        sample_once "$csv"
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

    # ③ 出报告
    report=$(awk -F'|' -v OUT="$out" '
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
            f_first = $13
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
        if (f_first ~ /^[0-9]+$/ && f_last ~ /^[0-9]+$/) havef = 1
        if (havef) {
            fdelta = (f_last + 0) - (f_first + 0)
            frtxt = f_first " → " f_last "（+" fdelta "）"
        } else {
            frtxt = "未采到（缺 python3 或虚拟屏 id 未知；不影响其它判据）"
        }

        printf "会话目录      : %s\n", OUT
        printf "样本数        : %d\n", n
        printf "时长          : %ds（%d 分 %d 秒）\n", dur, mins, secs
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
    sed -n '2,23p' "$0"
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
