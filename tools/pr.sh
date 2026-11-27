#!/usr/bin/env bash
# PR 操作封装（gh 优先，缺则回落 urllib）。末行固定 `PR: OK <cmd>` / `PR: FAIL <原因>`。
#
# 用法：
#   bash tools/pr.sh status <n>                         # 状态：draft/mergeable/head sha/CI
#   bash tools/pr.sh create --head <branch> [--title T] [--body-file F] [--base main]
#   bash tools/pr.sh ready <n>                          # 转正式（GraphQL；REST PATCH 会静默失效）
#   bash tools/pr.sh merge <n> --signoff                # squash + 双删分支 + ls-remote 自检
#   bash tools/pr.sh cleanup                            # 列非 main 分支（本地/远端）
#
# 铁律落地：
#   - merge 必须显式带 --signoff（对应「用户签发后才能合」），否则拒绝执行。
#   - merge 后强制 `ls-remote` 自检（今天漏删过远端分支）。
#   - token 只从 /root/.git-credentials 现读，绝不回显。
set -u

CRED="${CRED:-/root/.git-credentials}"
FAILED=""

fail() { [ -z "$FAILED" ] && FAILED="$1"; }

need_git() {
    git rev-parse --git-dir >/dev/null 2>&1 || { echo "PR: FAIL 不在 git 仓库内"; exit 1; }
}

load_token() {
    [ -r "$CRED" ] || return 1
    TOKEN=$(sed -E 's#.*:([^@]+)@.*#\1#' "$CRED")
    [ -n "${TOKEN:-}" ] && export GH_TOKEN="$TOKEN"
}

have_gh() { command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; }

repo_slug() {
    url=$(git config --get remote.origin.url 2>/dev/null)
    printf '%s' "$url" | sed -E 's#.*github\.com[:/]##; s#\.git$##'
}

# ---------- 子命令 ----------
cmd_status() {
    n="${1:-}"; [ -n "$n" ] || { echo "PR: FAIL 缺 PR 号"; exit 1; }
    if have_gh; then
        gh pr view "$n" --json number,title,isDraft,mergeable,headRefOid,url \
            --jq '"#\(.number) draft=\(.isDraft) mergeable=\(.mergeable)\n  head=\(.headRefOid)\n  \(.url)\n  \(.title)"' || fail "gh-view"
        gh pr checks "$n" 2>&1 | awk -F'\t' '{printf "  check: %s %s\n", $1, $2}' || true
    else
        slug=$(repo_slug)
        python3 - "$slug" "$n" <<'PY'
import json,os,sys,urllib.request
slug,n=sys.argv[1],sys.argv[2]
tok=os.environ.get('GH_TOKEN','')
r=urllib.request.Request('https://api.github.com/repos/%s/pulls/%s'%(slug,n),
    headers={'Authorization':'Bearer '+tok,'Accept':'application/vnd.github+json'})
d=json.load(urllib.request.urlopen(r,timeout=25))
print('#%s draft=%s mergeable_state=%s\n  head=%s\n  %s'%(d['number'],d['draft'],d.get('mergeable_state'),d['head']['sha'],d['html_url']))
PY
    fi
}

cmd_create() {
    head=""; title=""; body=""; base="main"
    while [ $# -gt 0 ]; do
        case "$1" in
            --head) head="$2"; shift 2;;
            --title) title="$2"; shift 2;;
            --body-file) body="$2"; shift 2;;
            --base) base="$2"; shift 2;;
            *) echo "PR: FAIL 未知参数 $1"; exit 1;;
        esac
    done
    [ -n "$head" ] || { echo "PR: FAIL 缺 --head"; exit 1; }
    [ -n "$title" ] || title=$(git log -1 --format=%s "$head" 2>/dev/null)
    args=(--draft --base "$base" --head "$head" --title "$title")
    [ -n "$body" ] && args+=(--body-file "$body")
    if have_gh; then
        gh pr create "${args[@]}" || fail "gh-create"
    else
        slug=$(repo_slug)
        python3 - "$slug" "$head" "$base" "$title" "${body:-}" <<'PY' || fail "api-create"
import json,os,sys,urllib.request
slug,head,base,title,body=sys.argv[1:6]
tok=os.environ.get('GH_TOKEN','')
payload={'title':title,'head':head,'base':base,'draft':True}
if body:
    payload['body']=open(body,encoding='utf-8').read()
r=urllib.request.Request('https://api.github.com/repos/%s/pulls'%slug,
    data=json.dumps(payload).encode(),
    headers={'Authorization':'Bearer '+tok,'Accept':'application/vnd.github+json','Content-Type':'application/json'},
    method='POST')
d=json.load(urllib.request.urlopen(r,timeout=30))
print(d['html_url'])
PY
    fi
}

cmd_ready() {
    n="${1:-}"; [ -n "$n" ] || { echo "PR: FAIL 缺 PR 号"; exit 1; }
    if have_gh; then
        gh pr ready "$n" || fail "gh-ready"
    else
        slug=$(repo_slug)
        python3 - "$slug" "$n" <<'PY' || fail "graphql-ready"
import json,os,sys,urllib.request
slug,n=sys.argv[1],sys.argv[2]
tok=os.environ.get('GH_TOKEN','')
def api(u,data=None):
    r=urllib.request.Request(u,data=json.dumps(data).encode() if data else None,
        headers={'Authorization':'Bearer '+tok,'Accept':'application/vnd.github+json','Content-Type':'application/json'})
    return json.load(urllib.request.urlopen(r,timeout=25))
node=api('https://api.github.com/repos/%s/pulls/%s'%(slug,n))['node_id']
q={'query':'mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{isDraft}}}','variables':{'id':node}}
print(json.dumps(api('https://api.github.com/graphql',q)))
PY
    fi
}

cmd_merge() {
    n="${1:-}"; [ -n "$n" ] || { echo "PR: FAIL 缺 PR 号"; exit 1; }
    signoff=0
    for a in "$@"; do [ "$a" = "--signoff" ] && signoff=1; done
    if [ "$signoff" != "1" ]; then
        echo "PR: FAIL 缺 --signoff（合并必须经用户签发；AGENTS §1/D-029）"
        exit 1
    fi
    have_gh || { echo "PR: FAIL merge 需要 gh（未安装或无 token）"; exit 1; }
    branch=$(gh pr view "$n" --json headRefName --jq .headRefName) || { echo "PR: FAIL 取分支名失败"; exit 1; }
    gh pr merge "$n" --squash --delete-branch || fail "gh-merge"
    # 收尾：回 main、拉平、删本地分支（先验树）、ls-remote 自检
    git checkout main >/dev/null 2>&1 && git pull -q origin main >/dev/null 2>&1 || fail "sync-main"
    if git rev-parse --verify -q "refs/heads/$branch" >/dev/null; then
        if [ "$(git rev-parse "main^{tree}")" = "$(git rev-parse "$branch^{tree}")" ]; then
            git branch -D "$branch" >/dev/null 2>&1 || fail "delete-local"
        else
            echo "  ⚠ $branch 与 main 树不同，未删（需人工确认）"
        fi
    fi
    left=$(git ls-remote --heads origin 2>/dev/null | awk '{print $2}' | grep -v 'refs/heads/main$' | tr '\n' ' ')
    if [ -n "${left// /}" ]; then echo "  远端仍有分支: $left"; fi
    echo "  main = $(git rev-parse --short main)"
}

cmd_cleanup() {
    echo "本地非 main 分支:"
    git for-each-ref --format='  %(refname:short)' refs/heads | grep -v '^  main$' || echo "  （无）"
    echo "远端非 main 分支:"
    git ls-remote --heads origin 2>/dev/null | awk '{print $2}' | sed 's#refs/heads/#  #' | grep -v '^  main$' || echo "  （无）"
}

# ---------- 入口 ----------
need_git
load_token || { echo "PR: FAIL 读不到凭据文件 $CRED"; exit 1; }
sub="${1:-}"; shift || true
case "$sub" in
    status)  cmd_status "$@";;
    create)  cmd_create "$@";;
    ready)   cmd_ready "$@";;
    merge)   cmd_merge "$@";;
    cleanup) cmd_cleanup;;
    *) echo "用法: bash tools/pr.sh {status|create|ready|merge|cleanup} ..."; exit 2;;
esac

echo "---"
if [ -n "$FAILED" ]; then echo "PR: FAIL $FAILED"; exit 1; fi
echo "PR: OK ${sub}"
exit 0