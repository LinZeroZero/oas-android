---
name: ui-review
description: 安卓真机 UI/渲染审查：截图取样、OCR/识别质量判读、收工定序，避免"整屏喂单行模型"这类方法论错误。
---

# ui-review — 安卓真机 UI 审查

## 适用场景
- 要看某个界面"长什么样/文字读得对不对"
- 验证 OCR、模板匹配、点击命中这类和画面有关的结论
- 真机实验前后要确状态、收尾

## 铁律（方法论，犯错最多的地方）
1. **单行 OCR 只喂单块 ROI**（约 200×70）。把整行/整屏横条喂给 rec，输出必然乱码，**但分数仍有 0.5+**——分数不能自证正确。
2. **非文字区用分值过滤**：实测美术/空白区 0.03–0.22，干净标签 0.73–0.79，`score ≥ 0.5` 可作经验阈值（样本值，不是产品阈值）。
3. **不改分辨率/dpi**：识别画布恒 **1280×720@320**；真机物理分辨率任意。
4. **游戏强制横屏**：阴阳师是 2412×1080 横屏，取样前先 `wm size` 确认，别按竖屏切 y 坐标。
5. **n=1 不下性能结论**（D-018）：要 ≥3 次（最好 5 次）取中位数 + 区间。

## 标准动作
```bash
wm size                                    # 物理分辨率
settings get system user_rotation          # 0 才继续
am start -n <pkg>/<activity>               # 起应用/游戏
sleep 20; screencap -p /sdcard/oas_shot.png   # 抓帧（前几帧常是加载页，看文件大小判断）
# 取样：按 ROI 裁，不按整行
```
proot 侧判读（无 cv2）：
```python
# 灰度/剪裁 → resize 到 h=32 → /255 → (x-0.5)/0.5 → NCHW → rec
# det 侧：长边 960、32 对齐、thr=0.3、连通域外接矩形外扩 15%
```

## 收工定序（缺一不算完，AGENTS §5）
1. `am force-stop <游戏/应用>`
2. 销毁所有 `oas-*` 虚拟屏（`dumpsys window displays | grep -c 'DisplayInfo{"oas"'` 应为 0）
3. `settings put system accelerometer_rotation 0`、`user_rotation 0`
4. 删本轮 `/sdcard/oas_*` 与 `/data/local/tmp` 临时文件
5. 杀自己起的后台进程（`pgrep -f 'logcat -s'` 应空）
6. **把 Operit 拉回前台**（`mCurrentFocus` 应为 `com.ai.assistance.operit/...`）

## 判读要点
- **帧是加载页还是真 UI**：加载页大面积纯色、文件体积小（1.2 MB vs 3.0 MB）；真 UI 文字密集。
- **游戏 UI 文案**：识别出错时先分"det 漏框"还是"rec 认错"——分别看框数和分数。
- 中文长句、描边、复杂背景是最难的一档，验证用它；合成 ASCII 只能验链路通不通。

## 期望输出
- 截图尺寸/环境（分辨率、rotation、应用版本）
- 逐 ROI：坐标、宽高、分数、识别文本
- 结论只写"这段链路通/不通、瓶颈在哪一段"，不写"看起来不错"
- 收工定序六项逐条核账
