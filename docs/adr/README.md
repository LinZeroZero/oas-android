# 架构决策记录（ADR）

本目录存放**架构级**决策记录。用途：以后回头看「当初为什么这么定」时有据可查，而不是靠聊天记录考古。

## 什么时候必须写 ADR

满足任一条件就必须写：

- 改变了进程/线程模型、启动方式、权限域（例：谁持有 WakeLock、谁做调度）
- 引入了新的跨进程协议或通信语义
- 决定「不做某事」（明确排除的替代方案也值得留档）
- 违反直觉、未来可能被人「顺手改回去」的决定

普通 bug 修复、重构、文档补充**不需要** ADR。

## 命名与格式

- 文件名：`NNNN-短横线小写标题.md`，四位编号自增，不复用编号
- 结构用 Nygard 模板：**状态 / 日期 / Context / Decision / Consequences**，另加「参考」列出依据（官方文档、同类项目、本项目实测证据）
- 状态取值：`Proposed` → `Accepted` → `Superseded by ADR-XXXX` / `Deprecated`
- **已成事实的决策不删除、不改写结论**；要推翻就新写一篇并在旧文顶上标 `Superseded by`

## 索引

| 编号 | 标题 | 状态 | 日期 |
|---|---|---|---|
| [0001](0001-daemon-owns-automation.md) | 自动化与阶段调度归 root daemon，app 退化为瘦客户端 | Accepted | 2026-11-14 |

## 与门禁的关系

ADR 不是文档装饰：`0001` 里的四条硬约束（root 域自检 / PARTIAL_WAKE_LOCK / 短连接 / driver 前移）已经在 `docs/BACKLOG.md` 与 `docs/WORKFLOW.md §4` 里对应到具体验收项。改代码如果违反 ADR，评审时按 **REVIEW: FAIL** 处理。