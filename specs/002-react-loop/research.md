# Phase 0 Research: ReAct 循环

**Feature**: `002-react-loop` | **Date**: 2026-09-16

Technical Context 中不存在 `NEEDS CLARIFICATION`（spec 的 5 处歧义已在 `/speckit.clarify` 中解决）。本文件记录**设计前必须定死的关键决策**，以及被否决的替代方案。

---

## D-01: core 与 provider 的 LLM 调用如何桥接

**Decision**: 扩展 core 的 `LlmClient` 签名为 `LlmResponse call(String sessionId, Profile profile, LlmRequest request)`（`LlmRequest` 携带 systemPrompt / history / tools）；在 `oryxos-provider` 新增 `ProviderServiceLlmClient implements LlmClient`，把 `LlmRequest` 映射为 `provider.Prompt`，并把 `provider.LlmResponse` 映射回 core 的 `LlmClient.LlmResponse`。

**Rationale**: 保持既有分层（provider → core，core 不依赖 provider）。`sessionId` 与 `Profile` 都定义在 core，签名扩展不产生循环依赖；`ReActLoop` 仍只依赖 `LlmClient`，可用 mock 做秒级单测。

**Alternatives considered**:
- 让 `ReActLoop` 直接依赖 `provider.ProviderService` → core 反向依赖 provider，破坏分层，**否决**。
- 另立一个 core 接口 `AgentLlmClient` → 与既有 `LlmClient` 职责重复、增加概念负担，**否决**。

---

## D-02: Bootstrap / SKILL.md 的解析方式与错误策略

**Decision**:
- `Profile.bootstrap.files`（相对 `.oryxos/` 的路径列表）视为**显式引用**：任一文件缺失 → 抛 `ContextLoadException`，终止本次输入组装。
- 未配置任何 bootstrap 文件时，按默认三件套顺序尝试加载 `AGENTS.md`、`SOUL.md`、`USER.md`；缺失仅记 WARN 并继续。
- `Profile.skills[].name` → 读取 `.oryxos/skills/<name>/SKILL.md`，缺失 → 抛错（显式引用）。
- `Profile.identity`（字符串）作为角色设定，拼在最前。
- 每次组装都重新读文件，不做任何缓存。

**Rationale**: 直接落实 FR-014/FR-015。"三件套"名称与含义取自 `docs/DemandAnalysis.md`（AGENTS.md 项目级 agent 行为、SOUL.md 人格定义、USER.md 用户偏好）。区分"显式引用"与"隐式默认"是同时满足"缺失要报错"和"缺失至少 WARN"的唯一自洽解释。

**Alternatives considered**:
- 所有缺失只 WARN → 违背 FR-015，且会出现"人格静默丢失"这类最难查的软故障，**否决**。
- 所有缺失都报错 → 未配置 bootstrap 的极简 Profile 无法运行，**否决**。

---

## D-03: 模型输入四部分的组装形态

**Decision**: 一次调用的输入 = ① 单个 system 消息，文本按序拼接：identity → Bootstrap 文件内容 → SKILL 文件内容 → 当前日期时间（`yyyy-MM-dd HH:mm:ss z`）；② 长期记忆（非空时）作为紧随其后的独立 system 消息；③ 最近 N 次完整往返的历史消息；④ 可用工具 schema（走请求的 tools 字段，不占消息位）。

**Rationale**: 与 FR-011 的"固定顺序"一一对应，便于 harness 逐段断言；把长期记忆独立成消息，才能与"会话历史"明确区分（spec 强调二者是两码事）。

**Alternatives considered**: 把记忆直接拼进 system 文本 → 无法稳定断言四部分顺序、与 FR-011 的表达不符，**否决**。

---

## D-04: "最近 N 次完整往返"的截断算法

**Decision**: 以 `user` 角色消息为边界，把消息列表切成"往返"序列；取最近 N 段（默认 20）；最后一段即使尚未闭合（即当前正在处理的那次往返）也计入；截断只作用于历史部分，system 设定与长期记忆不参与。

**Rationale**: 落实澄清 Q1 的口径。以 user 消息为边界可保证工具结果不会被拦腰截断（工具消息天然落在其所属往返内）。同时修复原有骨架按 `maxHistoryTurns * 2` 条消息估算导致的漂移（工具消息会额外占位）。

**Alternatives considered**: 按消息条数截断 → 与澄清口径不符，且实际保留的往返数会随工具调用次数漂移，**否决**。

---

## D-05: 循环的停止条件优先级

**Decision**: 每轮拿到响应后，**先累积进会话**，再**优先判断是否含工具调用**：含 → 顺序执行工具、回填结果、进入下一轮；不含 → 文本非空则以文本正常收尾，文本也为空则按 FR-016 以明确提示收尾。

**Rationale**: 与 FR-001/FR-003 一致。必须修复现有骨架"先看文本、有文本就返回"的顺序——模型常见行为是「先输出一句话，再给出工具调用」，按旧顺序会把这句话误判为最终答复，导致工具永不执行。

**Alternatives considered**: 保留"文本优先" → 直接违反 FR-001，**否决**。

---

## D-06: 整体耗时上限的执行方式

**Decision**: 处理开始时计算 deadline（默认 60 秒，取自 `settings.processing_timeout_seconds`）；在**步骤边界**检查是否超时——每轮循环开始前、每次工具调用前后、每次 LLM 调用返回后；超时抛 `ProcessingTimeoutException`（core），由 `AgentService` 依 FR-018 上抛给入口。不在调用中途强杀线程；单次外部调用的超时继续由底层连接器负责。

**Rationale**: 实现简单、无额外线程与中断语义，能覆盖"多轮累积超时"这一主要场景，且超时点必然留下日志（FR-021）。与 Constitution「核心阶段只交付运行时内核」一致。

**Alternatives considered**:
- 独立看门狗线程中断 → 引入线程中断与资源清理复杂度，SQLite/JDBC 的中断语义又差，核心阶段**否决**。
- 把"剩余时间预算"透传给能力一的 Provider → 需要改动已冻结的接口，收益不足，**否决**。

---

## D-07: `tool_invocations` 表结构与既有审计口径对齐

**Decision**: 与 `llm_calls` 同口径——保留 `status`，新增 `success`（整型布尔）、将现有 `error` 列改名为 `error_message`、新增 `latency_ms`；其余列不变（`invocation_id` / `session_id` / `profile_name` / `tool_name` / `parameters` / `result` / `started_at` / `completed_at`）。时间列使用**整数亲和性**（epoch 毫秒），沿用能力一踩坑后的约定。

**Rationale**: spec 的交付物明确要求含 `success` / `error_message`；列名与 `llm_calls` 对齐后，未来做统一审计查询无需写分支。整数时间列可避免 SQLite 的 TEXT 亲和性把时间戳转成字符串导致读回解析失败（能力一已发生过的真实故障）。

**Alternatives considered**: 只补 `success`、保留 `error` 列名 → 两张审计表列名不一致，后续统一查询要写分支，**否决**。

---

## D-08: 会话持久化的交付范围

**Decision**: 本特性交付 Session 持久化的**写路径与读回**：`AgentService` 在处理结束调用 `SessionManager.save(session)`，内部经 `SessionRepository` 把元数据与 `messages_json`（Jackson 序列化）写入 `sessions` 表；`SessionManager.get` 在内存未命中时从库读回。表结构由 `db/schema.sql` 手工维护。

**Rationale**: FR-009 与 SC-004 要求"处理结束即持久化、重启后可读"。`SessionEntity` 与 `SessionRepository` 已存在，直接复用，无需新依赖。

**Alternatives considered**: 只写内存、SQLite 落地推迟到能力五 → 不满足 SC-004，**否决**。

---

## D-09: 沙箱校验在 ToolExecutor 中的落位

**Decision**: `ToolExecutor` 在执行前调用 core 接口 `ToolInvocationValidator`（默认 no-op 实现）；后续由 `oryxos-tool` 提供基于既有 `SandboxChecker` 的实现。工具内部已有的白名单校验保持不变（纵深防御）。

**Rationale**: 落实 class-17 要求的"执行前必经校验"调用点，但不提前实现未定义的 Sandbox 规则（Constitution 范围控制 + spec Assumptions）。把接口放 core 还避免了 core 依赖 `oryxos-tool` 的反向依赖。

**Alternatives considered**: 在 `ToolExecutor` 内直接 `new SandboxChecker(...)` → 既耦合角色又在规则未定时过早固化，且造成 core → tool 反向依赖，**否决**。

---

## D-10: "当前 Agent 配置"上下文的实现

**Decision**: core 新增 `AgentContext`（`ThreadLocal<Profile>`，提供 `set` / `current` / `clear`）。`AgentService.process` 在入口 `set`、在 `finally` 中 `clear`；工具实现通过 `AgentContext.current()` 读取当前 Profile。

**Rationale**: 落实 FR-010，且不改动 `OryxTool` 接口签名（改签名会波及全部工具实现）。虚拟线程下每请求独占线程，天然隔离。

**Alternatives considered**: 给 `OryxTool.execute(Map)` 增加 Profile 参数 → 波及所有工具与既有接口，代价远大于收益，**否决**。

---

## D-11: 结构化日志的落点与内容

**Decision**: 沿用能力一的分步日志风格，在四处落日志：
- 处理开始/结束（INFO）：sessionId、profile 名、**runId**、停止原因、总轮数、总耗时；
- 每轮迭代（DEBUG）：轮次序号、是否有工具调用；
- 每次工具调用（DEBUG/INFO）：工具名、耗时、成败；
- 异常与超时（WARN/ERROR）：错误原因（超时另记已完成轮数与工具次数）。

**runId 的注入方式**：`AgentService` 在处理开始 `MDC.put("runId", runId)`、`finally` 中 `MDC.remove`，
`logging.pattern` 以 `%X{runId:-none}` 输出。这样循环、工具执行、存储层审计打出的每一行日志都自动带同一个
runId，无需逐处传参即可与 `agent_runs` 的记录对照（FR-021 / FR-022、SC-010 / SC-011）。
MDC 与 `AgentContext` 同生命周期，必须在 `finally` 一起清理，否则复用线程的下一次请求会串号。

**Alternatives considered**: 给 `ReActLoop` / `ToolExecutor` 逐处补 runId 参数 → 要改签名或新增上下文参数，
且漏一处就断链；MDC 一次注入、全层可见，**选用 MDC**。

敏感信息不入日志（密钥、工具完整入参只记长度/摘要；用户消息只落库，不进日志）。

**Rationale**: 满足 FR-021 与 SC-010（仅凭日志即可还原一次处理的全过程），且与能力一已建立的日志风格一致。

**Alternatives considered**: 只在异常路径记日志 → 不满足 SC-010，**否决**。

---

## D-12: Spring 装配位置

**Decision**: `oryxos-core` 新增 `config/CoreAutoConfiguration`（仅用 spring-context 的 `@Configuration` / `@Bean`，不引入 spring-boot 依赖），装配 `SessionManager`、`ContextLoader`、`PromptBuilder`、`ToolRegistry`、`ToolExecutor`、`ReActLoop`、`AgentService`；`oryxos-provider` 在既有 `ProviderAutoConfiguration` 内新增 `ProviderServiceLlmClient` 的 `@Bean`；`oryxos-storage` 的 `JpaToolInvocationAuditor` 以 `@Component` 形式被组件扫描（与既有 `JpaLlmCallAuditor` 一致）。

**Rationale**: 与能力一"模块自带装配"的做法一致，保持模块自洽；同时避免给 core 引入 Spring Boot 依赖（core 目前只依赖 spring-context）。

**Alternatives considered**: 把全部装配集中到 `oryxos-boot` → 让最薄的应用模块承担过多配置职责，模块边界变模糊，**否决**。

---

## D-13: 既有骨架的处置

**Decision**: 在既有骨架上演进而非重写：`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`ContextLoader`、`SessionManager`、`Profile` 均原地改造；`Profile` 需补充 `bootstrap.files` 字段与 `settings` 的便捷访问器（`maxIterations()` / `maxHistoryTurns()` / `processingTimeoutSeconds()`，带默认值 10 / 20 / 60）。

**Rationale**: 这些类目前仅为骨架、且无外部调用方（唯一引用是 `AgentApiController` 的占位文案），原地改造代价最低，也不会破坏能力一已交付并验证的部分（Provider / Profile 加载 / 审计）。

**Alternatives considered**: 新建一套并行类 → 会留下两套语义相近的循环实现，后续必然发散，**否决**。
