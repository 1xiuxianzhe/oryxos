# OryxOS Constitution

## Core Principles

### I. Java 21 + Spring Boot 3.x 单体应用
OryxOS 基于 JDK 21 和 Spring Boot 3.x 构建，采用 Maven 9 模块结构，最终打包为单二进制 fat JAR 部署。这是项目的基础技术栈，任何代码改动都必须遵守这一约束。

### II. 自实现 ReAct 循环
Agent 核心循环必须由 OryxOS 自行实现，不使用 Spring AI 的 Agent 抽象。ReActLoop 是 Agent 的大脑，负责驱动 Reason + Act 循环，控制 LLM 调用和 Tool 执行的流程。

### III. Spring AI 边界控制
Spring AI 在 OryxOS 中只用于 Provider 协议转换和 Tool Schema 生成。必须禁用 Spring AI 的自动 tool 执行机制，否则会出现 tool 被调两次的问题。Tool 的实际调度和执行完全由 ReActLoop + ToolExecutor 控制。

### IV. Tool 体系统一管理
所有 Tool 相关代码必须合并到 `oryxos-tool` 模块中，不拆分成多个子模块。SKILL.md 的加载归 `oryxos-core` 的 ContextLoader 负责，不是可执行的 Tool。

### V. Memory 三层记忆统一门面
Memory 系统必须通过 `MemoryService` 统一访问接口，对 ReAct 循环只暴露一个接口。内部再分会话记忆（委托 SessionManager）和长期记忆（委托 LongTermMemory），不得将 Memory 简化成与 Session 合并。

### VI. SQLite 审计表 day one 落库
`tool_invocations` 和 `llm_calls` 两张审计表必须在核心阶段就写入 SQLite，建立可审计的数据地基。查询接口和报表可在扩展阶段实现，但不能遗漏审计数据的收集。

### VII. 核心阶段范围控制
核心阶段只实现运行时内核，不引入扩展能力如：Tool 调用并行、上下文动态压缩、Agent 间任务委托、流式响应 SSE、认证机制、fallback/hedge racing、向量检索、情景记忆等。

## Additional Constraints

### 技术栈约束（不可协商）
| 项 | 选择 |
|---|---|
| 语言 / 运行时 | JDK 21+（Spring Boot 3.x 要求） |
| 应用框架 | Spring Boot 3.x 单体应用，单二进制部署（fat JAR） |
| LLM 调用 | Spring AI + Spring AI Alibaba（复用现成 connector） |
| Agent 核心循环 | 自实现 ReAct loop（不用 Spring AI 的 Agent 抽象） |
| HTTP 层 | Spring MVC + Java 21 virtual thread |
| 命令行 | Picocli（12 个子命令） |
| 配置解析 | SnakeYAML（Profile YAML） |
| 持久化 | SQLite + Spring Data JPA + `MEMORY.md` 文件 |
| MCP 集成 | MCP Java SDK（可能需要部分自实现） |
| 日志 | Logback + SLF4J（结构化） |
| 指标 | Micrometer + Prometheus（扩展阶段） |

### 模块边界约束
- Tool 相关合并为一个 `oryxos-tool` 模块，不拆成 builtin/skill/mcp 三个。
- SKILL.md 的加载归 `oryxos-core` 的 `ContextLoader`，不放在 Tool 模块。
- Memory 做成三层记忆统一门面（`MemoryService`），对 ReAct 循环只暴露一个接口。
- 扩展阶段加新 Channel / Tool 只加新模块，不动 core。

### 关键设计约束
1. **Provider 显式映射**：必须维护 `provider name → ChatModel` 的显式映射，不能靠类型扫描自动区分 Provider。
2. **ReAct 循环**：核心循环约数十行 Java，精简实现，默认 `MAX_ITERATIONS = 10`。
3. **上下文管理**：保留 system prompt 和最近 20 轮对话，超出丢弃，不做总结压缩。
4. **Sandbox**：用 Path/Pattern 白名单做应用层校验，不用 Java SecurityManager。
5. **SQLite 注意**：首次建表可用 update，后续表结构演进需手动维护建表脚本。
6. **审计落库**：`tool_invocations` 和 `llm_calls` 两张表核心阶段就写入。

## Development Workflow

### Spec-Kit 混合开发模式
- **主体开发阶段**：用 Spec-Kit 跑 spec-driven 流程（constitution → specify → plan → tasks → implement）
- **增量阶段**：小颗粒度改动（1~3 个文件）切换到手动提示词模式
- **每个 user story 结束后**：跑一次 `/speckit.analyze` 做跨 artifact 一致性检查
- **每个 user story 完成后**：git commit 标记，方便回退

### User Story 推进顺序
```
US-1（对接 LLM）→ US-2（ReAct 循环）→ { US-3（Memory）+ US-4（Tool）并行 } → US-5（Web Service 收口）
```

### 开发时的检查清单
- [ ] 是否违反 7 条 Constitution 原则？
- [ ] Spring AI 的自动 tool 执行是否已禁用？
- [ ] Provider 是否用了显式 name 映射？
- [ ] Tool 是否都归在 `oryxos-tool` 一个模块？
- [ ] SKILL.md 加载是否在 ContextLoader？
- [ ] Memory 是否通过 MemoryService 统一门面访问？
- [ ] 审计表是否已落库？
- [ ] 是否引入了核心阶段不该做的能力？

## Governance

### Constitution 约束
本 Constitution 是 OryxOS 项目的最高指导原则，任何代码改动都必须遵守。若与现有文档冲突，以 `docs/TechnicalSolution.md`（技术方案）为准。

### 修订流程
- 重大变更（MAJOR 版本）：需要项目核心成员评审和批准
- 新增原则（MINOR 版本）：需要文档更新和团队共识
- 修正和优化（PATCH 版本）：由维护者决定

### 合规要求
- 所有 PR 和代码审查必须验证 Constitution 合规性
- 复杂性必须得到充分论证
- 使用 AGENTS.md 作为运行时开发指导

**Version**: 1.0.0 | **Ratified**: 2026-09-01 | **Last Amended**: 2026-09-01

<!-- Sync Impact Report -->
<!-- Version change: None → 1.0.0 (initial creation) -->
<!-- Added sections: All core principles, additional constraints, development workflow, governance -->
<!-- Removed sections: None -->
<!-- Deferred items: None -->
<!-- End Sync Impact Report -->