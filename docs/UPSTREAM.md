# 上游（OnmyojiAutoScript）来源与许可

本文记录 `vendor/oas` 里那份上游代码**到底是从哪来的**、怎么复现、以及许可义务。
`vendor/` 不入 git（见 `.gitignore`），所以「可复现」全靠这份记录 + `tools/fetch_vendor.sh`。

## 1. 上游

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/nearaura/OnmyojiAutoScript |
| 类型 | Python 手游脚本（阴阳师 / Onmyoji），带 GUI 与 OCR/识别流水线 |
| 许可 | **GPL-3.0**（见上游 `LICENSE`；本仓库 `LICENSE` 同为 GPL-3.0，修改声明见 README） |
| 默认分支 | `dev`（**不是** `main`，也不是 `master`） |

## 2. 本地 pin（2026-11-14 实测确定）

```
url    = https://github.com/nearaura/OnmyojiAutoScript
ref    = dev
commit = f1ba2a5c504876b200a8bf4416a94c5631a870bb   (2026-11-12 21:33:30 +0800)
patch  = patches/oas-3.13.patch
```

**怎么确定的**（可重复验证，不是猜的）：

```bash
# ① 浅克隆上游默认分支
git clone --depth 1 https://github.com/nearaura/OnmyojiAutoScript /tmp/oasm
git -C /tmp/oasm rev-parse HEAD            # → f1ba2a5c…

# ② 逐个文件比对 blob 哈希（vendor/oas 不是 git 仓库，只能比内容）
for f in README.md gui.py requirements.txt module/base/utils/utils.py; do
  echo "$f ours=$(git hash-object vendor/oas/$f) up=$(git hash-object /tmp/oasm/$f)"
done
```

结果：`README.md` / `gui.py` / `requirements.txt` **哈希完全一致**；
`module/base/utils/utils.py` 是唯一不同的文件 —— 差异就是本仓库打的补丁
（`np.int` → `np.int64`，Python 3.13 / 新版 numpy 已移除 `np.int`），逐行 diff 与
`patches/oas-3.13.patch` 完全对应。

> 所以：`vendor/oas` = 上游 `dev@f1ba2a5c` + `patches/oas-3.13.patch`。

其他可用 ref（`git ls-remote --tags --heads` 于 2026-11-14 观测）：

- 分支：`dev`（默认，f1ba2a5c）、`master`（48f34cb0，较旧的一条线）、`solana`
- 标签：`oas-install-v0.0.1` / `-v0.0.2` / `-v0.0.3` / `oas-install-v1.0.0`（7d938c6b，2023-12-06）
  —— 这些 tag 都**早于**当前 `dev`，不要拿它们当 pin。

## 3. 拉取与打补丁

```bash
bash tools/fetch_vendor.sh                 # 默认 REF=dev，拉到 vendor/oas 并打补丁
REF=<tag|branch|sha> bash tools/fetch_vendor.sh   # 换 pin（例如回归到某个旧 tag）
bash tools/fetch_vendor.sh --check         # 只校验：vendor 是否 git 仓库 / 补丁是否已打 / pin 记录
```

脚本行为（幂等）：

- `vendor/oas` 不是 git 仓库时**就地 `git init`**，再 `fetch --depth 1` + `reset --hard`
  + `clean -xdf` 对齐上游 —— **不删目录重建**，避免误删本地改动/缓存；
- 已经是 git 仓库时只做 fetch + reset + clean；
- 补丁用 `git apply -p0`（**必须是 -p0**：补丁头是 `--- /tmp/utils.py.orig` /
  `+++ vendor/oas/...`，默认 `-p1` 会把路径削成 `oas/...` 然后报
  `No such file or directory`）；
- 已打过补丁（`--check --reverse` 通过）就跳过，重复执行不会失败；
- 成功后写 `vendor/oas/.oas-ref`（url / ref / commit / 时间 / 补丁路径），下次 `--check`
  可直接读它核对 pin。

## 4. 许可与归属（重要）

- 上游 OnmyojiAutoScript 为 **GPL-3.0**；本仓库是它的修改版/移植版，同样以 **GPL-3.0**
  发布，保留原作者信息，仅供学习交流。
- `vendor/oas` 属于「随仓库一起分发时要一并提供源码与许可」的部分：分发包（APK / zip）
  里若包含上游 Python 代码，必须同时给出 `LICENSE` 全文与本文件；
  见 `THIRD_PARTY_NOTICES.md` 的归属清单。
- 只做**兼容性补丁**（Python 3.13 / numpy 2.x 的 API 变更），不改变上游语义，
  不删除版权声明，不再额外附加限制。
- 上游代码**不复制进 `app/src`**：`vendor/` 只在构建/打包阶段被引用，避免把 GPL 代码
  和本项目自己的 Kotlin 代码混成一锅（许可边界仍在，只是物理上分开）。