# BG-32 长时挂机 PASS 路径：真机长跑（**作废，FAIL**）

- 日期：2026-11-15 22:48 起跑 → 2026-11-17 22:40 停止（**跨 47.8 小时**，中间无人值守）
- 分支 / commit：`main` @ `a056538`（PR #29 squash 后）
- APK：md5 `6b41f067d0fcf3d31e0a8f870d353083` / `48507773 B`（本轮未重装，与 `dist/` 一致）
- 设备：Android 15 / `1080x2412` / density `480`（**未改过分辨率与 dpi**）
- 结论：**FAIL —— 本轮不作为 P0-A 的 PASS 证据**，仅作缺陷发现记录

## 0. 一句话

脚本判据本身没坏（阴性对照、身份校验都过），但**这轮采样被「等待被中断」拉成了 47.8 小时，且游戏在中途消失**；同时暴露出一个真阻断：**`frames` 通路整轮 100% 取不到（819 次尝试全 `n/a`）**。

## 1. 起跑序列（原样）

```
22:48:23  am start -a android.intent.action.SET_TIMER --ei ...LENGTH 840   # 收工铃兜底
22:48:23  sh tools/hang_check.sh start
          输出目录  : /data/local/tmp/oas-hang/20261115-224823
          虚拟屏 id : -            （起跑时还没建屏）
          采样间隔  : 5s
          采样器 pid: 8594
          帧数采集  : app 通道（server.jar --cmd 'stats <id>'），每 5 拍取一次
22:48:24  am start -n org.onmyoji.oas.android/.MainActivity --ei keepalive 1 \
                    --ei kb_a 120 --ei kb_b 60 --ei kb_c 600
```

## 2. 中途实测（22:49:47 / 22:52:27，原样）

```
22:49:47  1794754182|Awake|1|35|8815|508|8865|5846|3325552|-|22.47|340712|-|S|S
22:52:27  1794754346|Awake|1|35|8815|1716|8865|14531|3332681|-|20.41|369424|n/a|S|S
```

app 侧 PoC 报告（`cache/keepalive_poc.txt`，写盘于耗时 1738s 时）：

```
OAS 后台保活验证（P1-1）  2/2 段通过（进行中）
displayId=35 已耗时 1738s
[OK] A 前台基线
    daemon 心跳 120/120 拍，空洞 1002ms（判据 <2000ms），虚拟屏帧 352→3955（delta=3603）
[OK] B 切后台（回桌面）
    daemon 心跳 1594/60 拍，空洞 1005ms（判据 <2000ms），虚拟屏帧 3988→15770（delta=11782）
```

> A 段 120/120 心跳、B 段帧持续增长是真数据；**C/D 段无任何记录**（phase 边界未写盘）。
> B 段「1594/60 拍」异常：该段实际被拉长到 ~29 分钟（app 进程被 freezer 冻住后阶段计时失真），非心跳真的跑了 1594 秒。

## 3. 停止时的报告原文（`sh tools/hang_check.sh stop`）

```
会话目录      : /data/local/tmp/oas-hang/20261115-224823
样本数        : 4093
时长          : 172287s（2871 分 27 秒）
采样最大间隔  : 891s（设定以外的空洞都在这里）
虚拟屏数量    : 1（末次）｜displayId=-
非 Awake 占比 : 3880/4093（94%）
wakelock      : -（末次）
loadavg1      : 21.01（末次）
MemFree       : 458640 kB（末次）
daemon        : 起始 pid 0 → 末次 pid 8815｜CPU +0 jiffies
游戏          : 起始 pid 0 → 末次 pid 0｜CPU +0 jiffies
surfaceflinger: CPU +77483 jiffies
末次进程状态  : daemon=S｜游戏=0（R/S 正常，D/T 疑似被冻）
疑似冻结样本  : daemon 4/4093｜游戏 0/4093（D/T 计数）
帧数          : 未采到（原因见下一行「帧数通路」；其它判据照常跑，不静默跳过）
帧数通路      : app 通道：app_process --cmd stats <id>（默认每 5 拍取一次）｜n/a 819 拍｜节流跳过 3274 拍
CSV 列完整性  : OK（每行都是 15 列）

判定: FAIL
依据: daemon 缺失 1/4093 个样本；游戏缺失 3807/4093 个样本；
```

## 4. CSV 实算时间线（`samples.csv`，4093 行）

| 项 | 值 |
|---|---|
| 首拍 / 末拍 | `2026-11-15 22:48:24` / `2026-11-17 22:39:51` |
| 游戏存在 | **286 拍，`22:48:30` → `23:17:25`**（之后 `pidof` 恒 0） |
| 首次非 Awake | `22:53:14`（= C 段 `input keyevent 223` 真的生效了） |
| 非 Awake 占比 | 3880 / 4093 = 94% |
| `frames` 取到数字 | **0 拍**（全部 `-` 或 `n/a`） |

## 5. FAIL 原因（按责任分）

1. **主因：等待被中断，采样器没停**（责任：主 AI）
   起跑后的等待只靠 `sleep`，没有「时钟兜底 / 定时自停」。用户中途插入消息后等待链断掉，采样器一路跑到 2 天后。
   → 修正：`hang_check.sh start` 之后必须同时起一个**硬超时自停**（如 `OAS_MAX_DURATION=900` 或 `(sleep 900; stop) &`），并写入交接包「已知坑」。
2. **真阻断：`frames` 通路整轮 100% `n/a`**（责任：脚本侧，待修）
   819 次尝试全失败、3274 拍被节流跳过。同时期 daemon 活着、proot python3 直连 socket 能取到帧 → 说明是 `daemon_cmd` 这条 app 通道没打通，不是「画面不动」。
   → 即便干净重跑 14 分钟，按现有判据最多只能得 **WARN**（`frames` 未生效），**拿不到 PASS**。
3. **待查：游戏在 `23:17:25` 消失**（虚拟屏仍在、daemon 仍活）
   可能是游戏自身退出 / 被系统回收。P0-A 若要防这一类，需在判据里区分「游戏自己没了」与「链路卡死」，不能都记成同一个 FAIL。

## 6. 已排除

- **不是脚本误判**：daemon/游戏/虚拟屏/冻结/CSV 列完整性五项本轮均正常判定，`daemon 4/4093` 冻结样本远低于 25% 阈值。
- **不是 daemon 死**：`8815` 一直存活到停止前；末次 `ndisp=1`。
- **不是熄屏没做**：`非 Awake 94%`，首次熄屏 `22:53:14` 与 C 段时序吻合。

## 7. 设备状态（收工后实查，已还给用户）

```
mWakefulness=Awake｜/sys/power/wake_lock 空
oas 虚拟屏 0 个｜daemon 0 个｜com.netease.onmyoji 无进程｜org.onmyoji.oas.android 无进程
accelerometer_rotation=0｜Operit 已回前台（22:40:11）
挂机遗留无：未改分辨率/dpi，未改旋转，未注册自启动
```

## 8. 后续

1. `frames` 通路根因定位（B 只读诊断）→ A5 修 → 门禁绿 → 再约真机长跑；
2. 长跑脚本侧加「硬超时自停」，杜绝再次出现无人值守 2 天；
3. 下一次长跑仍按「真熄屏 ~14 分钟 + 收工真响铃 → 亮屏 → 拉回 Operit」定序。

## 9. 相关文件

- 原始采样：`/data/local/tmp/oas-hang/20261115-224823/{samples.csv,report.txt,sampler.pid}`
- app 侧 PoC：`/data/user/0/org.onmyoji.oas.android/files/oas/cache/keepalive_poc.txt`、`keepalive_hb.jsonl`
- 工具实现：`tools/hang_check.sh`（`main` @ `a056538`）
