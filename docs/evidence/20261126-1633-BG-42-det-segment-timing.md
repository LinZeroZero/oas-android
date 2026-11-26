# BG-42 分段计时：det 的 4 s 在哪——**落在 `run` 段（跨语言编组 + ORT 推理，未分离）**

## 1. 日期 / 环境
- 2026-11-26 16:31–16:33（CST）；分支 `feat/bg42-seg-timing`（基于 main `ac62692`）
- APK `69,691,780 B`，md5 `2c0b6a9226d86ff881842471455a641d`（含分段计时探针）；每轮 `force-stop` + `logcat -c` + 冷启 + 等 24 s，共 3 轮
- 输入：PIL 合成两行 ASCII，600×240 → det 输入 **608×256**

## 2. 原始数据（logcat 原样，n=3）
```
iter1 {"n":2,"ms":4005.1,"seg":{"pre":5.8,"marshal":18.0,"run":3505.7,"post":268.8,"rec":194.3}}
iter2 {"n":2,"ms":4585.4,"seg":{"pre":7.3,"marshal":18.0,"run":4015.3,"post":302.7,"rec":228.6}}
iter3 {"n":2,"ms":4629.8,"seg":{"pre":8.4,"marshal":18.6,"run":4054.9,"post":304.4,"rec":229.6}}
```
（段定义，**按代码实测口径**：`pre`=PIL 缩放+归一；`marshal`=**仅** `flat.tolist()`（纯 Python list，**不出语言边界**）；
`run`=Kotlin `runDet(flat_list, …)` —— **含"Python list → Java float[]"的跨语言编组 + ORT 推理，二者未分离**；
`post`=BFS 连通域+映射+外扩；`rec`=逐框识别）

## 3. 统计（n=3，中位数）
| 段 | 中位数 | 占端到端（分母＝**中位数 4585.4 ms**） |
|---|---|---|
| `pre` | 7.3 ms | 0.2% |
| `marshal` | **18.0 ms** | 0.4% |
| **`run`（编组 + ORT 推理，未分离）** | **4015.3 ms** | **87.6%** |
| `post` | 302.7 ms | 6.6% |
| `rec` | 228.6 ms | 5.0% |

## 4. 结论（**已按 OAS-D2 2026-11-26 评审降级：不再断言"FloatBuffer 被证伪"或"真凶是 ORT"**）
- `marshal` 段 18 ms 只能说明 **`tolist()`（纯 Python）很便宜**；它**不代表**跨语言编组便宜。
- 真正把 Python list 变成 Java `float[]` 的编组发生在 **`run` 段内部**，与 ORT 推理**混在一起，本证据未能分离**。
- 因此目前能确定的只有量级：**编组+推理合计 ~4.0 s（87.6%，分母为中位数 4585.4 ms）**；`post` 302 ms（6.6%）、`rec` 229 ms（5.0%）、`pre` 7 ms（0.2%）。
- **下一步必须先分离 `run` 段**：用 **32×32 极小输入**对照（若 `run` 仍约 3 s ⇒ 是**固定开销/编组**，与图像尺寸及算子无关；若随尺寸显著下降 ⇒ 是算子）。这一步只加一次 print，不动检测逻辑。

## 5. 下一步（顺序已修正：先分离，再优化）
1. **32×32 极小输入对照**（必做，前置）：分离「固定开销/编组」与「算子」。
2. 若判为**编组** → `FloatBuffer`/直接内存直传（BG-42 原第一优先重新成立）。
3. 若判为**算子** → 试 `intra_op_num_threads=4/0`、NNAPI、降 `limit_side`、换更轻 det。
4. 无论哪种，`post` 300 ms 都可优化（BFS 向量化或降采样 8×）。

## 6. 未做
- 未改推理配置（本轮只量不改）；未在阴阳师真实界面测（合成图足够定位瓶颈）
- 未做 n≥5（分段计时 n=3 足够判断量级，D-018 的下限已满足）

## 7. 收工定序核账（AGENTS §5）
- app `force-stop`；`/data/local/tmp/oas_s.apk`、`/sdcard/oas_seg.txt` 已删
- `user_rotation=0`、`accelerometer_rotation=0`；游戏/app 进程 none；前台 = Operit
