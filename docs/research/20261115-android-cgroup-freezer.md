# cgroup 与进程冻结：调用链、uid 0 归属、Shizuku 姿势是否可搬（只读调研 第三轮）

> 时间：2026-11-15（设备时钟）｜作者：D（调研兼评审位），由主 AI 定稿落盘
> 定位：**增量**。不重复 `20261115-android-vdisplay-background.md`、`20261116-android-background-keepalive.md`、`docs/adr/0001-daemon-owns-automation.md` 已有结论，只补「冻结落在哪个 cgroup、uid 0 落在哪、Shizuku 那套要不要搬」。
> 证据分级：`[文档]`（页面已核 200）/ `[AOSP:文件:行]` / `[Shizuku:文件:行]` / `[实测]`（本机原始值，见 `docs/evidence/20261115-1452-BG-29-cgroup-probe.md`）/ `[未验]`。纪律：不转述博客。

## 0. 一句话结论

本机冻结的调用链是 `Freezer.setProcessFrozen → Process.setProcessFrozen → SetProcessProfiles(uid,pid,{"Frozen"})`；AOSP **不硬编码 `cgroup.freeze` 路径**，「写哪个文件」由设备 `cgroups.json` / `task_profiles.json` 决定——本机实测落点是 v2 的 `uid_x/pid_y/cgroup.freeze`。uid 0 daemon 是否会冻**取决于它有没有 `ProcessRecord`**，跟 cgroup 路径无关；本机 root shell 已在 `/`，所以 Shizuku 式切 cgroup 是**幂等空操作**，不是必需项。

## 1. 冻结落点（问题一）

- `[AOSP:services/core/java/com/android/server/am/Freezer.java:39-41]` `setProcessFrozen` → `Process.setProcessFrozen`；`:59-61` 的 `freezeBinder` 走独立 native（binder 冻结）。
- `[AOSP:frameworks/base/core/jni/android_util_Process.cpp:314-333]` freeze 时执行 `SetProcessProfiles(uid, pid, {"Frozen"})`，unfreeze 时 `{"Unfrozen"}` —— **profile 名到路径的映射在设备配置里，不在代码里**。
- 因此本机 v1 视图（`freezer:/`、`memory:/apps/uid_x`）只是兼容视图；真实的 `cgroup.freeze` 在 v2 的 `uid_x/pid_y` 下，与 `[实测]` 一致。
- `/dev/cg2_bpf`：Shizuku 把它当作「可切进去的 cgroup v2 挂载点」之一。`[AOSP:system/core/rootdir/init.rc]` 在 main 分支只能 grep 到 `mount bpf bpf /sys/fs/bpf`（`:394`）与 `bpfloader`（`:1076-1077`），**没有挂载 cg2_bpf 的行** → 该挂载点疑为设备/分支特定 `[未验]`。本机实测不存在该目录。

## 2. uid 0 `app_process` 的 cgroup 归属（问题二）

- 内核 fork/exec **继承父进程 cgroup**；AOSP 只在具体路径上显式迁移（app 进程靠 profile，第三方靠写 `cgroup.procs`）。
- 不存在「按 uid 自动分配 cgroup」的机制：要进 `uid_x` 必须显式写 `<cgroup>/cgroup.procs` —— 见 `[Shizuku:manager/src/main/jni/cgroup.cpp:7-19]`（`snprintf "%s/cgroup.procs"` 在 `:9`，`write` 在 `:16`）。
- 与实测吻合：`su`/`init` 全控制器在 `/` → daemon 进 `/` `[实测]`。

## 3. Shizuku 的启动姿势逐行（问题三）

- `[Shizuku:manager/src/main/jni/starter.cpp:116-142]` `start_server`：`fork()`（`:117`）→ 子进程 `setsid()`（`:125`）、`chdir("/")`（`:126`）、stdin/out/err 重定向 `/dev/null`（`:127-133`）→ `run_server`（argv 组装 + `execvp` `:111`）→ **父进程立刻 `exit(0)`（`:139`）**。这一条正是「su 会话退出、daemon 仍活」的原因。
- `[同文件:157-180]` `switch_cgroup()`：依次尝试 `/acct`（`:159`）→ `/dev/cg2_bpf`（`:163`）→ `/sys/fs/cgroup`（`:167`）→ `ro.config.per_app_memcg` 非 false 时试 `/dev/memcg/apps`（`:172-174`），全失败只 warn，不致命。
- 切 mount ns：`:203-206` `switch_mnt_ns(1)`。不切的话进程仍在 su/app 的 mount ns 里，看到的 `/data`、`/system` 与真机不一致（尤其 app 沙箱与 `/data/local/tmp` 视图）。
- **对本机是否必要**：`/sys/fs/cgroup` 可写且已有 312 个 `uid_*` 目录，但 uid 0 本来就在 `/` → 切过去是 no-op `[实测]`。→ **不必照抄**；值得抄的是 `setsid` + 父进程退出 + `--nice-name`（可识别进程名），与「脱离 `/apps/`」无关。

## 4. Android 15 / 16 有无新增限制（问题四）

- 已核页面（均 200）：`/about/versions/15/behavior-changes-15`、`/develop/background-work/services/fgs/service-types`、`/about/versions/16/behavior-changes-16`、`/about/versions/16/behavior-changes-all`。
- 未见针对常驻 `app_process` 的新限制；A15 的 FGS 时长上限指向 `dataSync` / `mediaProcessing` 等类型，`specialUse` 未见新增硬超时。→ **无明显变化**（就这 4 页）。

## 5. 机制 × uid 0 daemon（问题五）

| 机制 | 是否作用到 uid 0 daemon | 依据 |
|---|---|---|
| cached-app freezer | **否**（无 `ProcessRecord`，不改该进程 profile） | `[AOSP:Freezer.java:39-41]` + `[AOSP:OomAdjuster.java:4063-4091]` + `[实测]` v2 行为 `0::/` |
| binder freeze | 否（同一条 profile 路径） | `[AOSP:Freezer.java:59-61]` |
| lmkd | 否（`oom_score_adj=-1000` 不在击杀序列） | `[AOSP:ProcessList.java:1133 等]` `[实测]` -1000 |
| Doze / App Standby | 是（网络/任务），与 cgroup 无关 | `[文档]` doze-standby |
| phantom process killer | 否（只针对 app 派生进程） | `[文档/ADR-0001 §1]` |
| SoC suspend | 是（需内核 wake lock） | `[ADR-0001 §2]` |
| 厂商 Hans/Athena | `[未验]` | 闭源 |

**本机是否需要 Shizuku 式切 cgroup：不需要。**

## 6. 本轮新增未验清单

1. `cg2_bpf` 的挂载来源（`init.rc` 未见，疑设备或厂商 init）。
2. 本机 `task_profiles.json` / `cgroups.json` 的实际内容（`Frozen` profile 的路径映射）。
3. `cgroup.controllers` 为空的具体后果（是否影响后续 profile 应用）。
4. A16 页面对 `specialUse` 的细则未逐条读，只做了页级核验。
