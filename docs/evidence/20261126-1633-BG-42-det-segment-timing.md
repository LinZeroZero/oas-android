# BG-42 分段计时：det 的 4 s 在哪——**在 ORT 推理本身**

## 1. 日期 / 环境
- 2026-11-26 16:31–16:33（CST）；分支 `feat/bg42-seg-timing`（基于 main `ac62692`）
- APK `69,691,780 B`（含分段计时探针）；每轮 `force-stop` + `logcat -c` + 冷启 + 等 24 s，共 3 轮
- 输入：PIL 合成两行 ASCII，600×240 → det 输入 **608×256**

## 2. 原始数据（logcat 原样，n=3）
```
iter1 {"n":2,"ms":4005.1,"seg":{"pre":5.8,"marshal":18.0,"run":3505.7,"post":268.8,"rec":194.3}}
iter2 {"n":2,"ms":4585.4,"seg":{"pre":7.3,"marshal":18.0,"run":4015.3,"post":302.7,"rec":228.6}}
iter3 {"n":2,"ms":4629.8,"seg":{"pre":8.4,"marshal":18.6,"run":4054.9,"post":304.4,"rec":229.6}}
```
（`pre`=PIL+归一；`marshal`=`flat.tolist()` 跨语言编组；`run`=Kotlin `runDet` + ORT 推理；`post`=BFS 连通域+映射+外扩；`rec`=逐框识别）

## 3. 统计（n=3，中位数）
| 段 | 中位数 | 占端到端（~4.4 s） |
|---|---|---|
| `pre` | 7.3 ms | 0.2% |
| `marshal` | **18.0 ms** | 0.4% |
| **`run`（ORT 推理）** | **4015.3 ms** | **~91%** |
| `post` | 302.7 ms | 6.9% |
| `rec` | 228.6 ms | 5.2% |

## 4. 结论：原假设被证伪，真凶是 ORT 推理
- **BG-42 原定第一优先（`FloatBuffer` 直传解决 marshal）不成立**：marshal 只有 **18 ms**（0.4%）。
- **`runDet` 本身 3.5–4.1 s**，是 600×240 小图上**荒谬**的开销 —— 说明问题在**推理配置或模型本身**，不是数据搬运。
- `post` 300 ms 值得优化（BFS 是纯 python 逐点，可改向量化/降采样更狠），但它是次要项。
- rec 在 det 之后从 ~132 ms 涨到 ~230 ms（CPU 已被 det 占满后的排队效应，属正常）。

## 5. 下一步（候选，按性价比）
1. **线程数**：`OcrRuntime.init` 里 rec/det **共用** `intra_op_num_threads=2`；det 是重算子模型，试 4 或 0（=ORT 自选）。改 1 行、可量。
2. **NNAPI**：现在 init 传 `useNnapi` 可能为 false；det 试 NNAPI，不支持会自动回退 CPU。
3. **换更轻的 det**：PP-OCRv3 det 2.43 MB 不算大，但若 1/2 都无效，可试 `ch_PP-OCRv2_det`（更老更轻）或降 `limit_side`（608→480/320，精度换速度）。
4. **post 优化**：BFS 改 numpy 向量化或降采样 8×（现在 4×）。

## 6. 未做
- 未改推理配置（本轮只量不改）；未在阴阳师真实界面测（合成图足够定位瓶颈）
- 未做 n≥5（分段计时 n=3 足够判断量级，D-018 的下限已满足）

## 7. 收工定序核账（AGENTS §5）
- app `force-stop`；`/data/local/tmp/oas_s.apk`、`/sdcard/oas_seg.txt` 已删
- `user_rotation=0`、`accelerometer_rotation=0`；游戏/app 进程 none；前台 = Operit