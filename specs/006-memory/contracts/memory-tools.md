# Contract: 记忆工具（save_memory / recall_memory）

**契约对象**：两个 `io.oryxos.core.tool.OryxTool` 实现，位于 `io.oryxos.memory.MemoryTools`
**登记点**：`io.oryxos.memory.config.MemoryAutoConfiguration` 的登记器，写入共享 `ToolRegistry`
**执行链路**：既有 `ToolExecutor`（校验 → 解析 → 执行 → 审计），与其它内置工具同形态

> 不使用 Spring AI 的 `@Tool` 注解与自动执行（宪法 III / research P-02）。

## 1. save_memory

| 项 | 值 |
|---|---|
| 名称 | `save_memory` |
| 描述 | 把需要跨会话长期记住的事写进记忆；`scope` 选 `core`（用户身份、项目背景、关键约束等始终要带上的信息）或 `archival`（其余事实与历史），不确定就填 `archival` |
| schema | `{"type":"object","properties":{"content":{"type":"string"},"scope":{"type":"string","enum":["core","archival"]}},"required":["content"]}` |

**输入处理**

| 输入 | 行为 |
|---|---|
| `content` 缺失/空白 | 失败结果，`retryable=false`，提示缺少 `content` |
| `scope` 缺失/空白 | 按 `archival` 处理 |
| `scope` = `core`/`CORE`/`Core` | 写入核心区 |
| `scope` = `archival`/大小写变体 | 写入归档区 |
| `scope` 为其它值 | **失败结果**，`retryable=false`，提示合法取值（不得静默改写区块） |
| 写入 IO 失败 | **失败结果**，`retryable=true`，携带原因 |
| 成功 | `ToolResult.ok(...)`，确认文本包含"已记住"与落入的区块 |

**副作用**：向 `<workspace>/memory/MEMORY.md` 对应区块追加一条带日期的条目；不写任何其它文件（尤其不写 `USER.md`）。

## 2. recall_memory

| 项 | 值 |
|---|---|
| 名称 | `recall_memory` |
| 描述 | 按关键词在长期记忆的归档区检索（核心记忆每次都自动带上，不需要检索） |
| schema | `{"type":"object","properties":{"keyword":{"type":"string"}},"required":["keyword"]}` |

**输入处理**

| 输入 | 行为 |
|---|---|
| `keyword` 缺失/空白 | 失败结果，`retryable=false`，提示缺少 `keyword`（**不得**返回全量归档区） |
| 命中 | `ToolResult.ok(命中条目，换行连接)` |
| 未命中 | `ToolResult.ok("没有找到相关记忆")`（**不是**失败，也不抛异常） |
| 读取失败（文件缺失/IO） | `ToolResult.ok("没有找到相关记忆")`，同时 WARN |

**检索范围**：仅归档区；大小写不敏感；不做分词、不正则、不打分排序。

## 3. 登记与可见性

| # | 要求 |
|---|---|
| R-1 | 两个工具在应用启动后被注册进共享 `ToolRegistry`，启动日志能列出它们 |
| R-2 | 与其它内置工具一样受 `ToolResolver` 授权过滤：`Profile.tools` 为空 → 不限制（自动可用）；显式声明清单的 Agent 必须把工具名列进去 |
| R-3 | 工具重名时不覆盖既有工具（`ToolRegistry` 先到先得），且不得与 `read_file`/`shell`/`notify` 等既有名字冲突 |
| R-4 | 调用照常写入 `tool_invocations` 审计表（含参数、状态、耗时）——由既有 `ToolExecutor` 保证，不需要新代码 |
| R-5 | 工具**不**自行读取 `oryxos.workspace`，一律通过注入的门面间接访问记忆文件 |

## 4. 明确不做

- 不做"自动提炼"（会话结束/上下文快满时自动写入）：属扩展阶段。
- 不做 `save_memory` 的批量写入、删除、覆盖语义：记忆只追加。
- 不做记忆查询类工具（列出全部/统计）：核心阶段不提供查询接口。
