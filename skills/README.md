# skills/ —— Operit 技能源（版本化）

Operit 的技能**运行目录**是 `/sdcard/Download/Operit/skills/<name>/SKILL.md`（平台级、不在 git 里）。
本目录是**源**：改这里 → 拷过去 → 立即生效，换设备也能重建。

## 安装（每次改完跑一次）

```bash
SRC=<workspace>/skills
DST=/sdcard/Download/Operit/skills
mkdir -p "$DST"
for d in "$SRC"/*/; do
  n=$(basename "$d")
  rm -rf "$DST/$n"
  cp -r "$d" "$DST/$n"
done
ls -1 "$DST"
```

## 现有技能
| 目录 | 用途 |
|---|---|
| `github-ops/` | 本仓 GitHub 运维：分支/PR/评审/合并/清场 + 本仓踩坑处置 |
| `code-review/` | 按 WORKFLOW §5 八条 + gate 12 项评审，产出 PASS/CHANGES 与证据 |
| `ui-review/` | 安卓真机 UI/OCR 审查：ROI 取样、判读、收工定序 |

## 规范要点（来自 `operit_editor:how_make_skill`）
- 每个技能一个目录，必备 `SKILL.md`
- 顶部 frontmatter 写 `name` / `description`
- 正文：适用场景 → 执行步骤 → 约束 → 期望输出
- 可选 `scripts/` `templates/` `examples/` `assets/`，用相对路径引用
- **不要**放进 `docs/`：gate ⑨ 会扫描 `docs/**/*.md` 的反引号路径，误报死链
