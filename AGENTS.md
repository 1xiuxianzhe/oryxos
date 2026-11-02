# AGENTS.md

本文件是 OryxOS 项目的 AI 编程助手（CodeBuddy / Claude Code / Cursor / Copilot 等）开发指导与约束文件，遵循 agents.md 通用约定。开发本项目任何代码前，必须先阅读本文件与 `docs/` 下的四份文档，确保理解项目的定位、架构边界与关键决策，避免跑偏。

> **事实来源**：`docs/` 下四份文档写于规划阶段，部分细节（Profile 体系、工具/端点数量、实施节奏）已被实现超越。**当前以代码与 `README.md` 为准**，文档冲突时不按旧文档改代码。

前置必读文档（按顺序）：
1. `docs/industryResearch.md` —— 业界调研，理解「为什么做」与项目定位
2. `docs/DemandAnalysis.md` —— 需求文档，回答「做什么」（What）
3. `docs/TechnicalSolution.md` —— 技术方案，回答「怎么做」（How）
4. `docs/AIProgrammingGuide.md` —— AI 编程实施思路（Spec-Kit 混合模式）

---

## 1. 项目定位（一句话）

OryxOS 是基于 **Java（JDK 21 + Spring Boot 3.x）** 实现的、面向企业场景的 **Agent OS**（Agent 操作系统）。它装在企业自己的 K8s / 服务器上，作为统一底座运行各种业务 Agent，共享渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业基础设施内，不锁云生态。

**核心分层判断（贯穿所有开发决策）：**

> agent runtime（让单个 Agent 跑起来） ≠ Agent OS（让一群 Agent 在企业里被管起来）。

- **核心阶段**交付的是 **运行时内核（地基）**，能力上对齐业界开源 Agent OS 的基础层。
- **企业级治理层**（多租户、SSO、完整审计、Tool Policy）是扩展阶段的**终局差异化**，核心阶段不做。

开发时务必区分「当前要做的是内核，不是完整企业级 Agent OS」。

---

## 2. 技术栈约束（不可协商）

| 项 | 选择 |
| --- | --- |
| 语言 / 运行时 | JDK 21+（Spring Boot 3.x 要求） |
| 应用框架 | Spring Boot 3.x 单体应用，单二进制部署（fat JAR） |
| LLM 调用 | Spring AI + Spring AI Alibaba（复用现成 connector） |
| Agent 核心循环 | **自实现 ReAct loop**（不用 Spring AI 的 Agent 抽象） |
| HTTP 层 | Spring MVC + Java 21 virtual thread |
| 命令行 | Picocli（9 个顶层子命令） |
| 配置解析 | SnakeYAML（Agent `AGENT.md` frontmatter / legacy Profile YAML）+ Spring Boot YAML |
| 持久化 | SQLite + Spring Data JPA + `MEMORY.md` 文件 |
| MCP 集成 | MCP Java SDK（已落地：`mcp_servers.yaml`，stdio / sse） |
| 日志 | Logback + SLF4J（结构化） |
| 指标 | Micrometer + Prometheus（扩展阶段） |

---

## 3. 七条不可违背的开发原则（Constitution）

这些原则来自需求文档设计目标 + 技术方案关键技术决策，任何代码改动都必须遵守：

1. **JDK 21 + Spring Boot 3.x 单体应用，Maven 多模块（9 个），单二进制部署。**
2. **五大核心能力优先，支撑模块次之。** 核心阶段交付运行时内核，企业级治理层放扩展阶段。
3. **自实现 ReAct loop**，不直接用 Spring AI 的 Agent 抽象。
4. **Spring AI 只用一半**（最容易写错，务必注意）：
   - ✅ 用它的 Provider 抽象与协议转换（OpenAI 兼容连接器）。
   - ❌ **禁用它的自动 tool 执行**（否则 tool 会被调两次）。
   - Tool 的 Schema 由 `OryxTool.getInputSchema()` 显式提供，调度与执行完全由 `ReActLoop` + `ToolExecutor` 控制。
5. **工具扩展低门槛优先**：MCP server（配置即接入，可复用现成实现）→ 实现 `OryxTool` 并注册为 Spring Bean（重代码）。`skills/`、`REFERENCE.md` 属于 prompt 侧按需知识，**不是工具**，不要混进 ToolRegistry。
6. **核心阶段 SQLite + MEMORY.md 存储，向量检索放扩展阶段。** 审计的 `agent_runs`、`tool_invocations`、`llm_calls` 三张表**核心阶段就写入落库**（day one 立起可审计地基；`agent_runs` 两阶段写：RUNNING → FINISHED）。
7. **每个 user story 完成后有可演示 demo，优先级是「跑通」而非「完美」。**

其中 **原则 4（Spring AI 只用一半）** 和 **原则 6（审计 day one 落库）** 是最容易被 AI agent 忽略、又必须守住的两条。

---

## 4. 五大核心能力（对应 5 个 User Story）

| 能力 | 说明 | 核心模块 |
| --- | --- | --- |
| ① 对接 LLM | Provider 抽象层，Agent 不感知具体模型 | `oryxos-provider` |
| ② ReAct 循环 | Agent 大脑，Reason + Act 循环 | `oryxos-core`（ReActLoop） |
| ③ Memory | 核心阶段：会话记忆（SessionManager）+ 长期记忆（`MEMORY.md`，核心区 + 归档区） | `oryxos-memory` |
| ④ Tool 体系 | 内置 10 个 Tool（文件/命令/HTTP/通知/记忆/演示天气）+ MCP 扩展 + 自实现 `OryxTool` | `oryxos-tool` |
| ⑤ Web Service | REST API 覆盖 Agent 管理、会话、审计、工作区、工具与 Provider 目录 | `oryxos-web` |

**Channel**（消息接入渠道）是核心功能模块，但不算「五大核心能力」之一。核心阶段只内置 CLI 一种，IM Channel（企业微信/飞书/钉钉/Slack）放扩展阶段。

---

## 5. Maven 工程结构（9 个模块，不可随意增改）

```
oryxos-core          核心引擎：ReActLoop、PromptBuilder、ToolExecutor、AgentLoader/WorkspaceWatcher、AgentLifecycleService、AgentScheduler、Session、Profile、OryxTool、MemoryService 接口、审计接口。所有模块依赖它
oryxos-provider     能力一：ProviderService、Function Calling 适配、显式 provider name → ChatModel 映射
oryxos-memory       能力三：DefaultMemoryService（门面实现）、LongTermMemory、MemoryFiles、MemoryTools
oryxos-tool         能力四：内置 Tool（File/Shell/Http/Notify/MockWeather）、MCP Client、ToolRegistry、Sandbox（合一个模块）
oryxos-web          能力五：REST Controller（Agent/会话/审计/工作区/目录）、管理台静态资源、GlobalExceptionHandler、OpenAPI
oryxos-channel-cli  支撑：CLI Channel 实现
oryxos-storage      支撑：SQLite 存储层（sessions + agent_runs / llm_calls / tool_invocations）
oryxos-cli          支撑：Picocli 命令行入口（9 个顶层子命令）、工作区布局
oryxos-boot         支撑：Spring Boot 启动模块，打成 fat JAR
```

> `admin-web/` 是管理台前端源码（React 18 + Vite 5），不属于 Maven 模块；构建产物进 `oryxos-web/src/main/resources/static/admin/`。

**模块边界约束（技术方案明确，勿违反）：**
- Tool 相关**合并为一个 `oryxos-tool` 模块**，不拆成 builtin/skill/mcp 三个。
- **Agent 定义以目录为准**：`.oryxos/agents/<name>/AGENT.md`（frontmatter 配置 + 正文指令）是主路径；legacy `.oryxos/profiles/*.yaml` 仍兼容但无热加载。新增能力优先只在目录 Agent 上实现，不要为 legacy 引入新的运行时机制。
- **`skills/` 与 `REFERENCE.md` 的加载归 `oryxos-core` 的 `ContextLoader`**，不放在 Tool 模块（它们是 prompt 侧知识，不是可执行的 Tool）。注意：`AGENT.md` frontmatter 里写 `skills:` 是硬错误。
- Memory 做成**长期记忆的统一门面**（`MemoryService` 接口在 core、实现 `DefaultMemoryService` 在 `oryxos-memory`），对 ReAct 循环只暴露一个接口；会话记忆仍由 `SessionManager` 承担、由 `PromptBuilder` 第三部分拼装，**门面不重复输出会话历史**（否则同一内容注入两次），也**不要把 Memory 简化成跟 Session 合并**。
- 扩展阶段加新 Channel / Tool 只加新模块，不动 core。

---

## 6. 关键设计约束（最容易写错的地方，务必遵守）

1. **Provider 显式映射**：不能靠「扫描容器里所有 ChatModel」区分 Provider（Bean 类型相同会有歧义）。必须维护 `provider name → ChatModel` 的显式映射（Qualifier 或配置表），Profile 通过 provider name 引用。

2. **ReAct 循环**：核心循环约数十行 Java，精简实现，不依赖 Spring AI 的 Agent 抽象。默认 `MAX_ITERATIONS = 10`，可在 Profile 覆盖。每轮把 LLM 响应和 Tool 结果累积到 Session 对话历史。

3. **上下文管理**：核心阶段策略简单——保留 system prompt 和最近 N 轮对话（`maxHistoryTurns`，默认 20 轮），超出丢弃，不做总结压缩。

4. **Sandbox**：核心阶段用 **Path/Pattern 白名单**做应用层校验，共四个维度：文件路径（`allowed-paths`）、Shell 命令（`allowed-commands`，首个 token 精确匹配）、出站域名（`allowed-domains`，host 模式）、脚本解释器（`allowed-interpreters`）。**任一清单为空 = 该维度一律拒绝**。**不用 Java SecurityManager**（JDK 17 起废弃、JDK 21 已不可用）。完整容器级沙箱（bwrap/Docker/K8s pod）放扩展阶段。

5. **SQLite 注意**：`hibernate.ddl-auto=update` 在 SQLite 上对表结构演进支持很弱。首次建表可用 update，后续表结构演进不要依赖 update 自动迁移，需手动维护建表脚本或引入 Flyway/Liquibase。

6. **审计落库**：`agent_runs`（一次提问，两阶段写 RUNNING → FINISHED）、`llm_calls`（每次模型调用）、`tool_invocations`（每次工具调用）三张表核心阶段就**写入**，通过 `run_id` 关联；查询与报表放扩展阶段。不要用「日志就够了」的心态漏掉。表结构在 `oryxos-storage/src/main/resources/db/schema.sql` 手工维护。

7. **核心阶段不做**：Tool 调用并行（多 Tool 按顺序执行）、上下文动态压缩、Agent 间任务委托（spawn sub-agent）、流式响应 SSE、认证机制、fallback/hedge racing、向量检索、情景记忆、Provider/通知渠道的运行时增删改（只读 API，改配置需重启）、沙箱白名单的 REST 修改、MCP server 运行期热增删（启动时连接一次）。

---

## 7. User Story 推进顺序（按依赖，不按时间）

```
US-1（对接 LLM）→ US-2（ReAct 循环）→ { US-3（Memory）+ US-4（Tool）并行 } → US-5（Web Service 收口）
```

- US-1 是基础（没有 LLM 调用一切跑不起来）
- US-2 依赖 US-1（ReAct 每轮都要调 LLM）
- US-3 与 US-4 并行依赖 US-2（Memory 注入 ReAct prompt，Tool 被 ReAct 调用）
- US-5 依赖前 4 个，对外暴露所有能力

**5 个验收 demo 对应关系：**
- Demo 一（查天气穿衣）← US-1 + US-2
- Demo 二（跨对话记偏好）← US-3
- Demo 三（零代码 PR digest）← US-4
- Demo 四（同步调用）+ Demo 五（多端点联动）← US-5

---

## 8. 开发协作模式（Spec-Kit 混合）

依据 `docs/AIProgrammingGuide.md`：

- **主体开发阶段**：用 Spec-Kit 跑 spec-driven 流程（constitution → specify → plan → tasks → implement）。已有需求文档/技术方案作为输入，不重写。
- **增量阶段**：小颗粒度改动（1~3 个文件）切换到手动提示词模式，不走完整 Spec-Kit 流程。
- **每个 user story 结束后跑一次 `/speckit.analyze`** 做跨 artifact 一致性检查（防漂移，硬性环节不可省）。
- **每个 user story 完成后 git commit 标记**，方便回退。

---

## 9. 开发时的检查清单（提交前自查）

- [ ] 是否违反 7 条 Constitution 原则？
- [ ] Spring AI 的自动 tool 执行是否已禁用？
- [ ] Provider 是否用了显式 name 映射（而非类型扫描）？
- [ ] Tool 是否都归在 `oryxos-tool` 一个模块？
- [ ] `skills/`、`REFERENCE.md` 的加载是否在 ContextLoader（而非 Tool 模块）？
- [ ] Memory 是否通过 MemoryService 统一门面访问？会话历史是否仍由 SessionManager 独立承担？
- [ ] 审计三表（agent_runs / llm_calls / tool_invocations）是否都已落库，`run_id` 是否贯穿？
- [ ] Agent 目录热加载路径（WorkspaceWatcher → AgentLifecycleService）是否未被绕过？非法配置是否保留旧注册？
- [ ] 是否引入了核心阶段不该做的能力（并行 Tool、SSE、认证、Provider 运行时 CRUD 等）？
- [ ] 是否使用了 JDK 21 特性、未引入 SecurityManager？

---

## 10. 验收标准速览

- **功能**：五大核心能力全部跑通，每个模块至少一个端到端测试。
- **性能**：单节点 ≥10 个 Agent、≥100 并发 Session、Session 创建 P99 < 200ms、内部转发开销 < 50ms（不达标不影响发布但需优化）。
- **场景**：5 个 demo Agent 全部跑通（发布的硬条件）。

---

*本文件提炼自 `docs/` 下四份文档并按当前实现校正。若与文档冲突，以**代码与 `README.md`** 为准；若与 Constitution 原则冲突，先停下来说明，不要顺手改掉原则。*
