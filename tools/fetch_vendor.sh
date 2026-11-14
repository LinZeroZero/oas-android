#!/usr/bin/env bash
# 拉取上游 OnmyojiAutoScript 到 vendor/oas，并打上本仓库的补丁
#
#   bash tools/fetch_vendor.sh                 # 用 REF（默认 dev）拉取到 vendor/oas
#   REF=oas-install-v1.0.0 bash tools/fetch_vendor.sh  # 指定 tag / 分支 / commit
#   bash tools/fetch_vendor.sh --check         # 只校验本地 vendor 状态（不联网、不写文件）
#
# 幂等：
#   * vendor/oas 还不是 git 仓库（例如手工拷贝进来的）→ 就地 git init 后 fetch，
#     再用 reset --hard + clean -xdf 对齐到上游，**不删目录重建**
#   * 已经是 git 仓库 → fetch 后 reset --hard 更新到目标 REF
#   * 补丁已经打过（reverse --check 通过）就跳过，不会重复打、不会失败
#
# 约定（见 docs/UPSTREAM.md）：
#   * vendor/ 不入 git（.gitignore 排除），靠本脚本 + .oas-ref 复现
#   * 上游是 GPL-3.0：只做兼容性补丁，不改变许可，分发时随附 LICENSE
set -u

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/nearaura/OnmyojiAutoScript}"
# 上游默认分支是 dev（2026-11-14 实测：clone 下来的 HEAD 是 origin/dev，
# refs/heads/dev=f1ba2a5c…；master 是另一条较旧的线，不存在 main）
REF="${REF:-dev}"

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
cd "$ROOT" || exit 2

VENDOR="vendor/oas"
PATCH="patches/oas-3.13.patch"
REF_FILE="$VENDOR/.oas-ref"

MODE="fetch"
for arg in "$@"; do
    case "$arg" in
        --check) MODE="check" ;;
        -h|--help|help)
            sed -n '2,22p' "$0"
            exit 0
            ;;
        *)
            echo "未知参数: $arg（支持 --check）" >&2
            exit 2
            ;;
    esac
done

say() { echo "[vendor] $1"; }

patch_state() {
    # 输出：applied | pending | broken | missing
    # 注意必须用 -p0：本仓库的补丁头是 `--- /tmp/utils.py.orig` / `+++ vendor/oas/...`，
    # 默认 -p1 会把路径削成 oas/module/... 然后报 "No such file or directory"。
    if [ ! -f "$PATCH" ]; then
        echo "missing"
    elif git apply -p0 --check --reverse "$PATCH" >/dev/null 2>&1; then
        echo "applied"
    elif git apply -p0 --check "$PATCH" >/dev/null 2>&1; then
        echo "pending"
    else
        echo "broken"
    fi
}

# ---------------------------------------------------------------- #
# --check：只读校验
# ---------------------------------------------------------------- #
if [ "$MODE" = "check" ]; then
    BAD=0
    if [ ! -d "$VENDOR" ] || [ ! -f "$VENDOR/README.md" ]; then
        say "FAIL vendor/oas 不存在或不完整（先跑 bash tools/fetch_vendor.sh）"
        BAD=1
    fi
    if [ -d "$VENDOR/.git" ]; then
        say "repo   $UPSTREAM_URL @ $(git -C "$VENDOR" rev-parse --short HEAD 2>/dev/null) ($(git -C "$VENDOR" describe --tags --always 2>/dev/null))"
    else
        say "repo   vendor/oas 不是 git 仓库（无法追溯 pin，建议重跑 fetch）"
        BAD=1
    fi
    ST=$(patch_state)
    case "$ST" in
        applied) say "patch  $PATCH 已应用" ;;
        pending|broken|missing)
            say "patch  $PATCH 未应用（$ST）"
            BAD=1
            ;;
    esac
    if [ -f "$REF_FILE" ]; then
        say "pin    $(tr '\n' ' ' <"$REF_FILE" | sed 's/  */ /g')"
    else
        say "pin    $REF_FILE 缺失（fetch 后会生成）"
    fi
    if [ "$BAD" = "0" ]; then
        say "CHECK: OK"
        exit 0
    fi
    say "CHECK: FAIL"
    exit 1
fi

# ---------------------------------------------------------------- #
# fetch
# ---------------------------------------------------------------- #
if [ ! -f "$PATCH" ]; then
    say "缺少补丁文件 $PATCH"
    exit 1
fi

mkdir -p vendor

if [ ! -d "$VENDOR/.git" ]; then
    say "vendor/oas 不是 git 仓库 → 就地 init（不删目录，保留已有文件）"
    mkdir -p "$VENDOR"
    git -C "$VENDOR" init -q || exit 1
    git -C "$VENDOR" remote remove origin >/dev/null 2>&1
    git -C "$VENDOR" remote add origin "$UPSTREAM_URL" || exit 1
else
    say "vendor/oas 已是 git 仓库 → 更新远程地址与内容"
    git -C "$VENDOR" remote set-url origin "$UPSTREAM_URL" 2>/dev/null ||
        git -C "$VENDOR" remote add origin "$UPSTREAM_URL" || exit 1
fi

say "fetch $REF from $UPSTREAM_URL"
if ! git -C "$VENDOR" fetch --depth 1 origin "$REF"; then
    say "拉取失败（网络/权限/REF 不存在？）: REF=$REF"
    exit 1
fi
git -C "$VENDOR" reset --hard -q FETCH_HEAD || exit 1
# 上游的工作树必须干净：本地临时文件（含旧的补丁残渣）一律清掉
git -C "$VENDOR" clean -xdfq || exit 1

ST=$(patch_state)
if [ "$ST" = "applied" ]; then
    say "补丁已在（reset 后仍 applied，说明上游已自带）→ 跳过"
elif [ "$ST" = "pending" ]; then
    if git apply -p0 "$PATCH"; then
        say "补丁已应用: $PATCH"
    else
        say "打补丁失败: $PATCH（上游结构变了？先 git apply --check 看冲突）"
        exit 1
    fi
else
    say "补丁状态异常（$ST）：可能上游已包含等价修改，需人工核对 $PATCH"
    exit 1
fi

COMMIT=$(git -C "$VENDOR" rev-parse HEAD)
{
    echo "url=$UPSTREAM_URL"
    echo "ref=$REF"
    echo "commit=$COMMIT"
    echo "fetched_at=$(date -Iseconds)"
    echo "patch=$PATCH"
} >"$REF_FILE"

say "OK commit=$COMMIT ref=$REF"
say "下次校验：bash tools/fetch_vendor.sh --check"
exit 0