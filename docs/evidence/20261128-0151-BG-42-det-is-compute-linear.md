# BG-42 定论：det 4 s 是**算子计算**（随像素线性），编组可忽略

## 1. 日期 / 环境
- 2026-11-28 01:50（CST）；分支 `feat/bg42-seg-timing`（评审 ③ 修复后）
- APK **69,692,124 B**；每轮 `force-stop` + `logcat -c` + 冷启 + 等 26 s，n=3

## 2. 本轮改动（对应评审 ③ 与 32×32 对照）
1. `predict_det.py`：把 `FloatArray → numpy`（原 `list(raw)`）从 `post` 拆出独立 `unmarshal` 段；若 buffer 协议可用则零拷贝
2. `android_entry.det_probe`：同一进程内再跑一次 **32×32 极小输入**，输出 `tiny.seg`

## 3. 原始数据（logcat 原样，n=3）
```
iter1 ms=4351.8  seg{pre 6.6, marshal 20.2, run 4023.1, unmarshal 0.1, post 2.1}  tiny{ms 36.5  run 35.3}
iter2 ms=4235.6  seg{pre 6.6, marshal 17.8, run 3971.5, unmarshal 0.1, post 2.5}  tiny{ms 31.5  run 30.7}
iter3 ms=4409.2  seg{pre 6.7, marshal 18.4, run 4144.4, unmarshal 0.1, post 2.3}  tiny{ms 28.8  run 27.9}
```

## 4. 三点结论（都可复核）
1. **`post` 的 300 ms 是假象**：拆出 `unmarshal` 后，`post` 只有 **2.1–2.5 ms**，反编组 **0.1 ms**。评审 ③ 判断正确（原 `list(raw)` 被算进 post）。
2. **编组全线可忽略**：`marshal` 18–20 ms、`unmarshal` 0.1 ms、`post` ~2 ms → 合计 **< 25 ms（0.6%）**。所以 **`FloatBuffer` 直传不会解决问题**（这次是**有数据**的结论，不是推断）。
3. **`run` 是算子计算，且随像素线性**：
   | 输入 | 像素 | `run` 中位 |
   |---|---|---|
   | 32×32 | 1,024 | **31.3 ms** |
   | 608×256 | 155,648 | **4,023 ms** |
   → 像素 152× ，耗时 **~129×**（≈线性）。单位成本约 **2.6×10⁻⁵ s/px**，**比应有水平慢 1–2 个数量级**，说明是"能用但极低效"的推理路径（2 线程 CPU、可能 debug、无 EP 加速）。

## 5. 下一步（方向已收敛，不再是猜测）
| 优先 | 手段 | 依据 |
|---|---|---|
| 1 | **降输入分辨率**（`limit_side` 960→480 或 320；或按 ROI 只检局部） | 线性关系 ⇒ 面积减半，耗时减半 |
| 2 | **换 EP / 线程**（NNAPI、`intra_op_num_threads` 4/0） | 当前 2 线程 CPU 明显吃满 |
| 3 | 更轻 det 模型 | 若 1、2 都不够 |
| ~~4~~ | ~~`FloatBuffer` 直传~~ | **已排除**（编组 <25 ms） |

## 6. 未做
- 未做 1/2 的实测（本轮只定位）；未在真实游戏界面测；n=3

## 7. 收工定序核账（AGENTS §5）
- app `force-stop`；`/sdcard/oas_c.txt`、`/data/local/tmp/oas_c.apk` 已删
- `user_rotation=0`、`accelerometer_rotation=0`；app/game 进程 none；前台 = Operit