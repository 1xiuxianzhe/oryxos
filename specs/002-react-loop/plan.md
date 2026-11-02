# Implementation Plan: ReAct 循环（Agent 大脑）

**Branch**: `002-react-loop` | **Date**: 2026-09-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-react-loop/spec.md`
（需求来源：`docs/class/第17节：ReAct 原理解析、实现与代码讲解.md`）

**Note**: 本计划严格沿用项目既定技术栈（JDK 21 + Spring Boot 3.x + Spring AI + SQLite/JPA + SnakeYAML + Jackson + Maven 9 模块），**不引入任何新依赖**。

## Summary

交付「能力二：ReAct 循环」——Agent 的大脑。对每条用户消息驱动"推理—行动—观察"循环：经能力一的 Provider 调模型、经 ToolExecutor 执行工具、把每轮响应与工具结果累积回 Session，直到模型不再请求工具或触达边界（最大轮数 / 整体耗时上限）。

同时交付四块配套能力：
1. **编排入口**：统一承接 CLI / Web / 定时三种触发源，管理"当前 Agent 配置"上下文的生命周期（含异常路径必须清理）。
2. **模型输入组装**：四部分固定顺序（角色与启动设定 → 长期记忆 → 最近 N 次完整往返 → 可用工具），历史按"往返"截断，末尾附当前日期时间。
3. **上下文供给**：读取 Bootstrap 三件套与 SKILL.md，每次重新读取不缓存；显式引用缺失报错、隐式缺失告警。
4. **审计与可观测**：工具调用成功/失败都落 `tool_invocations`；每次处理输出可还原全过程的结构化日志。

技术路径：core 只依赖自身抽象（`LlmClient`、`OryxTool`、`ToolInvocationAuditor`），由 `oryxos-provider` 提供 `LlmClient → ProviderService` 的适配实现，保持"provider 依赖 core、core 不依赖 provider"的既有分层不变。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**: Spring Boot 3.3.5（spring-context / spring-boot）、Spring AI 1.1.2（`spring-ai-model`、`spring-ai-openai`）、SnakeYAML 2.2、Jackson 2.17（databind）、SLF4J + Logback、SQLite JDBC + Spring Data JPA（Hibernate community dialect）。**新增依赖：无**。

**Storage**: SQLite（`${user.dir}/.oryxos/oryxos.db`）。本特性新增 `tool_invocations`、`sessions` 两张表的手工建表脚本与列演进迁移脚本（`llm_calls` 已由能力一交付）；Bootstrap / SKILL.md / Profile 仍在文件系统。

**Testing**: JUnit 5 + Mockito + AssertJ（`spring-boot-starter-test`），命令 `mvn test`。harness 全部为单测、不碰网络（Provider、工具、文件系统均可 mock 或使用临时目录）；真实模型验证另设手动运行的集成用例（默认跳过）。

**Target Platform**: 服务端（JDK 21，单二进制 fat JAR；Spring MVC + Java 21 virtual threads）

**Project Type**: Maven 多模块单体应用（9 模块）：`oryxos-core / provider / memory / tool / web / channel-cli / storage / cli / boot`

**Performance Goals**: 本特性不引入额外网络或磁盘往返（历史组装为纯内存操作）；单次处理的额外开销远低于一次模型调用；harness 单测总时长 < 30 秒、零网络依赖（SC-006）。沿用项目整体目标：单节点 ≥10 个 Agent、≥100 并发会话。

**Constraints**: 自实现循环（不得启用 Spring AI 自动 tool 执行）；核心阶段不做工具并行、Agent 委托、流式输出、上下文压缩；沙箱具体规则由后续 Sandbox 能力落地（本特性只保证"执行前必经校验"的调用点）；不引入新依赖。

**Scale/Scope**: 交付物见下方「实际交付物（产出记录）」表；harness 5 个测试类 + 3 个存储侧测试 + 1 个手动集成用例；默认值：最大轮数 10、历史 20 次完整往返、单次处理整体耗时上限 60 秒。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本计划如何满足 | 结论 |
|---|------|----------------|------|
| I | JDK 21 + Spring Boot 3.x 单体、Maven 9 模块、单二进制 | 全部改动落在既有 9 模块内，不加模块、不加依赖 | ✅ |
| II | 自实现 ReAct 循环 | `ReActLoop` 自行实现，不使用 Spring AI 的 Agent / 循环封装 | ✅ |
| III | Spring AI 只用协议转换 + schema，禁用自动执行 | 循环内手工调度；能力一已置 `internalToolExecutionEnabled(false)`；本特性不新增任何自动执行路径 | ✅ |
| IV | Tool 体系统一 + SKILL.md 归 ContextLoader | 工具调度集中在 core 的 `ToolExecutor`；SKILL.md 由 core 的 `ContextLoader` 加载，不进入 `oryxos-tool` | ✅ |
| V | Memory 经 MemoryService 统一门面 | `PromptBuilder` 只依赖 core 的 `MemoryReader` 抽象，实现仍在 `oryxos-memory` | ✅ |
| VI | 审计 day one 落库 | 新增 `ToolInvocationAuditor` + `JpaToolInvocationAuditor`，成功/失败都写 `tool_invocations` | ✅ |
| VII | 核心阶段范围控制 | 不做并行 / 委托 / 流式 / 压缩；不引入 fallback、熔断、向量检索 | ✅ |

**Gate 结果**：全部通过，无需要豁免的违规项 → Complexity Tracking 留空。

## Project Structure

### Documentation (this feature)

```text
specs/002-react-loop/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/           # Phase 1 输出
│   ├── agent-runtime.md
│   ├── llm-bridge.md
│   └── tool-invocations.sql
├── checklists/
│   └── requirements.md
├── spec.md
└── tasks.md             # Phase 2 输出（由 /speckit.tasks 生成）
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/
│   ├── agent/       AgentService（编排入口）、AgentContext（当前 Agent 上下文，ThreadLocal）
│   ├── react/       ReActLoop、PromptBuilder、ToolExecutor、StopReason、ProcessingTimeoutException
│   ├── context/     ContextLoader（Bootstrap 三件套 + SKILL.md，不缓存）
│   ├── llm/         LlmClient（签名扩展为 sessionId + Profile）、LlmRequest
│   ├── audit/       LlmCallAuditor（既有）、ToolInvocationAuditor（新增）
│   ├── tool/        OryxTool、ToolRegistry、ToolResult（既有）、ToolInvocationValidator（新增，默认 no-op）
│   ├── session/     Session、SessionManager（新增持久化写入与读回）
│   ├── profile/     Profile（补 bootstrap.files 与 settings 便捷访问器）、ProfileLoader / ProfileRegistry（既有）
│   └── config/      CoreAutoConfiguration（装配 core 各组件）
└── src/test/java/io/oryxos/core/
    ├── react/       ReActLoopTest、PromptBuilderTest、ToolExecutorTest
    ├── agent/       AgentServiceTest
    └── context/     ContextLoaderTest

oryxos-provider/
└── src/main/java/io/oryxos/provider/
    └── client/      ProviderServiceLlmClient（core LlmClient → ProviderService 适配）

oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── audit/       JpaLlmCallAuditor（既有）、JpaToolInvocationAuditor（新增）
│   ├── entity/      ToolInvocationEntity（补 success / error_message / latency_ms）、SessionEntity（既有）
│   └── repository/  ToolInvocationRepository、SessionRepository（既有）
├── src/main/resources/db/
│   ├── schema.sql   # 追加 tool_invocations、sessions 建表（手工脚本）
│   └── migration/   # 追加列演进脚本
└── src/test/java/io/oryxos/storage/
    └── repository/  ToolInvocationRepositoryTest

oryxos-boot/
├── src/main/java/io/oryxos/   OryxosApplication（既有）
└── src/test/java/io/oryxos/   ReactLoopSmokeIT（手动运行，真实模型验证 Demo 一）
```

**Structure Decision**: 保持 Maven 9 模块不变。循环与编排属"引擎层"，全部落在 `oryxos-core`；Provider 适配落在 `oryxos-provider`（依赖方向 provider → core）；审计与持久化落在 `oryxos-storage`（storage → core）；`oryxos-boot` 仅作为应用装配与手动集成验证的落脚点。

## Complexity Tracking

> 无 Constitution 违规项，本表留空。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| — | — | — |

## Post-Design Constitution Re-check

Phase 1 产出（`data-model.md` / `contracts/` / `quickstart.md`）复核后：

- `LlmClient` 的签名扩展仍在 core 内闭环，未引入 core → provider 的反向依赖 ✅
- `tool_invocations` 与 `llm_calls` 同口径（`success` / `error_message` / `latency_ms`），时间列沿用整数亲和性 ✅
- 未引入并行、委托、流式、压缩、fallback、熔断、向量检索等扩展阶段能力 ✅
- 无新增第三方依赖 ✅

结论：Gate 复核通过，无需豁免。

---

## 实际交付物（产出记录）

| 模块 | 文件 | 说明 |
| --- | --- | --- |
| oryxos-core | `react/ReActLoop.java` | 主循环：先累积再判工具调用、轮数兜底、整体耗时上限（工具执行后补检查点）；`runResult` 返回结局元数据（停止原因/轮数/工具次数/token/耗时），`run` 为兼容重载 |
| oryxos-core | `react/PromptBuilder.java` | 四部分组装：identity+Bootstrap+SKILL+当前时间 / 长期记忆 / 最近 N 次完整往返 / 工具 schema |
| oryxos-core | `react/ToolExecutor.java` | 固定顺序：查表 → 前置校验 → 解析参数 → 执行 → 写审计；错误一律降级为失败结果 |
| oryxos-core | `react/StopReason.java`、`react/ProcessingTimeoutException.java` | 停止原因与超时异常 |
| oryxos-core | `agent/AgentService.java`、`agent/AgentContext.java`、`agent/AgentConfigurationException.java` | 处理编排、当前 Agent 上下文（ThreadLocal，异常路径也清理）、Profile 缺失反馈 |
| oryxos-core | `context/ContextLoader.java`、`context/ContextLoadException.java` | Bootstrap 三件套 + SKILL 加载：无缓存、显式引用报错、默认缺失只 WARN |
| oryxos-core | `llm/LlmClient.java`、`llm/LlmRequest.java` | 签名扩展为 `call(sessionId, profile, request)`；`LlmResponse` 归一化并提供 `hasToolCalls()` |
| oryxos-core | `audit/ToolInvocationAuditor.java`、`audit/NoopToolInvocationAuditor.java` | 工具审计接口与空实现（与 `LlmCallAuditor` 同口径） |
| oryxos-core | `tool/ToolInvocationValidator.java`、`tool/NoopToolInvocationValidator.java` | 执行前校验扩展点（Sandbox 能力后续接入） |
| oryxos-core | `session/Session.java`、`session/SessionManager.java`、`session/SessionStore.java` | 会话恢复能力 + 持久化端口 + 内存/持久化双模式 |
| oryxos-core | `profile/Profile.java` | 新增 `bootstrap.files` 与 `settings` 便捷访问器（10 / 20 / 60，非法值 WARN 回退） |
| oryxos-core | `config/CoreAutoConfiguration.java` | core 引擎层装配（可选协作者用 `ObjectProvider`，含请求级审计 `AgentRunAuditor`） |
| oryxos-provider | `client/ProviderServiceLlmClient.java` | core `LlmClient` → `ProviderService` 适配（记忆转独立 system 消息、工具只翻译不执行） |
| oryxos-provider | `config/ProviderAutoConfiguration.java` | 新增 `LlmClient` 与 `ProfileRegistry` Bean |
| oryxos-storage | `audit/JpaToolInvocationAuditor.java` | 工具审计落库（成功/失败都写；写失败记 ERROR 不阻断） |
| oryxos-storage | `session/JpaSessionStore.java` | 会话持久化（`messages_json` 序列化；写失败记 ERROR 不阻断） |
| oryxos-storage | `entity/ToolInvocationEntity.java`、`repository/ToolInvocationRepository.java` | 新增 `success`/`error_message`/`latency_ms` 与 `findBySessionId` |
| oryxos-storage | `resources/db/schema.sql` | 追加 `tool_invocations`、`sessions` 手工建表 |
| oryxos-storage | `resources/db/migration/2026-09-16-tool-invocations-audit-columns.sql` | 既有库列演进脚本 |
| oryxos-boot | `src/test/java/io/oryxos/ReactLoopSmokeIT.java` | 真实模型 + 本地假工具的端到端冒烟（默认跳过） |
| 工作区 | `.oryxos/profiles/default.yaml` | 补 `bootstrap.files` 与 `settings` 示例 |
| oryxos-core | `audit/AgentRunAuditor.java`、`audit/NoopAgentRunAuditor.java` | 请求级审计接口与空实现（两阶段写：started / finished） |
| oryxos-core | `react/ReActResult.java`、`react/ModelCallException.java`、`react/StopReason.java`、`react/ProcessingTimeoutException.java` | 循环结局元数据（停止原因/轮数/工具次数/token）、模型失败专用异常、终态枚举扩展、超时异常携带已完成进度 |
| oryxos-core | `agent/AgentService.java`、`agent/AgentContext.java` | 请求级审计编排（七种终态都落库 + 每个终态一行带停止原因/耗时的日志）、`runId` 经 AgentContext 与 MDC 双通道传递、失败路径也持久化会话 |
| oryxos-storage | `audit/JpaAgentRunAuditor.java`、`entity/AgentRunEntity.java`、`repository/AgentRunRepository.java` | 请求级审计落库（RUNNING → FINISHED；缺 started 记录时补写，绝不丢记录） |
| oryxos-storage | `entity/LlmCallEntity.java`、`entity/ToolInvocationEntity.java`、`audit/JpaLlmCallAuditor.java`、`audit/JpaToolInvocationAuditor.java` | 明细补 `run_id`，从处理上下文取当前请求并关联；无上下文时照常落库 |
| oryxos-storage | `resources/db/schema.sql`、`resources/db/migration/2026-09-18-agent-runs-and-run-id.sql` | `agent_runs` 建表 + 明细补 `run_id` 的迁移脚本 |

**测试产出**：`ReActLoopTest`(12)、`PromptBuilderTest`(6)、`ToolExecutorTest`(8)、`AgentServiceTest`(14)、
`ContextLoaderTest`(5)、`CoreAutoConfigurationTest`(3)、`AgentRunRepositoryTest`(7)、`ToolInvocationRepositoryTest`(2)、
`JpaSessionStoreTest`(3)、`ProviderServiceLlmClientTest`(4)，以及既有测试（`ProfileLoaderTest` 5、`ProviderServiceTest` 7、
`ToolSchemaAdapterTest` 4、`ProviderConfigValidatorTest` 6、`ProviderAutoConfigurationTest` 3、`LlmCallRepositoryTest` 2）。
合计 **91** 个测试；另有三个默认跳过的真实调用用例（`ReactLoopSmokeIT`、`AskAiIT`、`ReactLoopRealRunIT`）。

## 实现过程中的偏差与修正

1. **会话持久化的分层**：计划原写"`SessionManager` 经 `SessionRepository` 落库"，但 core 不得依赖 storage。
   改为在 core 定义 `SessionStore` 端口、由 storage 提供 `JpaSessionStore` —— 与 `LlmCallAuditor` 同一模式。
2. **`ProfileRegistry` 的来源**：`CoreAutoConfiguration` 无法自建 Profile 索引（索引由启动扫描产生），
   因此在 provider 侧新增 `ProfileRegistry` Bean，供 `AgentService` 注入。
3. **超时用例的可测性**：`ReActLoop` 增加包内可见的 `LongSupplier clock` 构造器，使超时验证无需真实等待
   （否则单测会被迫 sleep ≥1 秒）。
4. **时间格式修正**：`LocalDateTime` 配 `z`（时区）模式会抛 `DateTimeException`，改用 `ZonedDateTime`。
5. **`LlmResponse` 归一化**：`toolCalls` 统一为非 null 列表并提供 `hasToolCalls()/hasText()`，避免调用方到处判空。
6. **桥接契约测试**：`contracts/llm-bridge.md` 的验证要点以 `ProviderServiceLlmClientTest` 落地
   （未在 tasks.md 单列，作为契约验证补充）。
7. **验收覆盖补齐（`/speckit.analyze` 复核后）**：新增 `JpaSessionStoreTest`（会话"重启后可读"读回路径）与
   `CoreAutoConfigurationTest`（core 装配齐备性 + 缺失 `LlmClient`/`ProfileRegistry` 时的清晰报错）；
   SC-006 的时长口径由不可度量的"1 秒级"改为"harness 单测 < 30 秒、零网络依赖"；`spec.md` 同步补上此前遗漏的
   FR-021 / SC-010 与第 5 条澄清记录，并消解 FR-008 与 FR-020 的措辞冲突。
8. **请求级审计与"失败也要有迹可循"（本次扩展）**：文档第 17 节的 `AgentService` 骨架是"`run` 成功后再保存会话"，
   而 spec 的 US3 场景 3 要求"无论成功还是抛异常都持久化"——实现照文档写了，验收却按更严的口径打了勾，属**过度声称**。
   本次一并修正：(a) 会话改到 `finally` 落库；(b) 新增 `agent_runs` 请求级审计（两阶段写，成功/达上限/空响应/超时/
   模型失败/配置错误/内部错误七种终态都落库）；(c) `llm_calls` / `tool_invocations` 补 `run_id` 与请求关联；
   (d) `ReActLoop` 抽出 `runResult`，把停止原因/轮数/工具次数/token 汇总交给审计；(e) 模型失败包成 `ModelCallException`，
   使 `MODEL_ERROR` 与 `INTERNAL_ERROR` 可区分；(f) 存储侧测试库改为每次从全新库开始，避免"老库缺新列"再次阻塞测试；
   (g) 日志统一经 MDC 携带 `runId`，使一次处理的所有日志可与 `agent_runs` 的记录对照，并补齐四种失败终态的处理级日志
   （配置错误此前一行日志都没有、`INTERNAL_ERROR` 完全无日志）。

## 范围外（明确后置，本特性不做）

以下两项经实现评审确认**后置**，不属于本特性的缺口——`/speckit.analyze` 复核时按此口径核对：

| 项 | 现状 | 归属 |
|----|------|------|
| Agent 循环接入具体通道 | `AgentApiController`（web）与 `oryxos chat`（CLI）仍是占位实现；本特性交付的是统一入口 `AgentService`，"把请求从通道接进来"属通道能力 | 能力五（Web / Channel） |
| 内置工具的启动装配 | 应用启动时 `ToolRegistry` 为空（`MemoryTools` 的 save/recall 已实现，但无装配入口）；本特性只交付"调度与执行"与注册机制，具体内置工具由后续能力提供 | 能力四（Tool） |

**本特性的验证方式**：直接调 `AgentService` 驱动真实模型与自实现循环（`ReactLoopRealRunIT` / `ReactLoopSmokeIT`），
审计三层记录落进 `.oryxos/oryxos.db` —— 已实测通过。"从 HTTP / CLI 压出审计记录"的端到端验证留待通道能力交付后再做。

## 验证记录

```bash
# 全量回归
mvn test
# → core 53 + provider 24 + storage 14 = 91 个测试全部通过；10 个模块 BUILD SUCCESS

# 真实模型端到端（Demo 一：多步任务一次办成），需先设置环境变量
#   Windows: set DEEPSEEK_API_KEY=xxx
mvn -pl oryxos-boot -am test -Dtest=ReactLoopSmokeIT -Dsurefire.failIfNoSpecifiedTests=false
# → Tests run: 1, Failures: 0, Errors: 0
# → 模型自主调用 get_weather 工具 → 工具结果回填 → 给出穿搭建议；
#    会话中同时存在 assistant（含 [Tool Request]）与 tool（含工具结果）消息，最终答复不含工具请求
```

**人工确认项（quickstart 第 2 节）**：死循环兜底（恰好 10 轮）、上下文截断（保留最近 20 次往返且工具消息不被截断）、
即改即生效（无缓存）、缺文件策略（显式报错 / 默认仅 WARN）、审计失败不阻断、`AgentContext` 异常路径清理、
会话重启读回（`JpaSessionStoreTest`）、结构化日志可还原处理全过程（日志带 `runId`，可与 `agent_runs` 对照）、
请求级审计七种终态都留痕（`AgentServiceTest`）—— 均已由 harness 覆盖并通过。
