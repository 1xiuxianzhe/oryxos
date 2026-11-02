---

description: "Task list for ReAct 循环（Agent 大脑）"
---

# Tasks: ReAct 循环（Agent 大脑）

**Input**: Design documents from `/specs/002-react-loop/`

**Prerequisites**: [plan.md](./plan.md)（必需）、[spec.md](./spec.md)（用户故事与优先级）、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)、[quickstart.md](./quickstart.md)

**Tests**: 本特性**要求测试**——spec 明确列出 5 个 harness 测试类与 1 个仓储测试（`docs/class/第17节` 的"验收 harness"锚点）。测试任务必须**先写、先失败**，再实现。

**Organization**: 任务按用户故事分组，每个故事可独立实现、独立测试、独立交付。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、且不依赖未完成的任务）
- **[Story]**: 所属用户故事（US1 / US2 / US3 / US4）
- 每条任务都带确切的文件路径

## Path Conventions

本特性是 Maven 多模块单体应用，路径以仓库根为起点：

- 核心引擎：`oryxos-core/src/main/java/io/oryxos/core/…`，测试在 `oryxos-core/src/test/java/io/oryxos/core/…`
- Provider：`oryxos-provider/src/main/java/io/oryxos/provider/…`
- 存储：`oryxos-storage/src/main/java/io/oryxos/storage/…`，脚本在 `oryxos-storage/src/main/resources/db/…`
- 应用启动：`oryxos-boot/src/main/java/io/oryxos/…`，手动集成用例在 `oryxos-boot/src/test/java/io/oryxos/…`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 共享基础设施——审计/会话表的手工建表脚本，以及被多个故事复用的纯新增类型。

- [x] T001 [P] 在 `oryxos-storage/src/main/resources/db/schema.sql` 追加 `tool_invocations` 与 `sessions` 建表（列与索引严格按 `specs/002-react-loop/contracts/tool-invocations.sql`；索引只建在既有列上，时间列用整数亲和性）
- [x] T002 [P] 新增迁移脚本 `oryxos-storage/src/main/resources/db/migration/2026-09-16-tool-invocations-audit-columns.sql`（为既有库补 `success`/`error_message`/`latency_ms`，脚本内注明适用场景与重复执行行为）
- [x] T003 [P] 新增 `oryxos-core/src/main/java/io/oryxos/core/react/StopReason.java`（枚举：`FINAL_ANSWER` / `EMPTY_RESPONSE` / `MAX_ITERATIONS` / `TIMEOUT` / `MODEL_ERROR`）
- [x] T004 [P] 新增 core 异常类型：`oryxos-core/src/main/java/io/oryxos/core/react/ProcessingTimeoutException.java`、`oryxos-core/src/main/java/io/oryxos/core/context/ContextLoadException.java`、`oryxos-core/src/main/java/io/oryxos/core/agent/AgentConfigurationException.java`（消息必须可读、含出错对象名）

**Checkpoint**: 表结构脚本就位，后续所有实现都有落库位置。

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有用户故事都依赖的契约与装配。**此阶段完成前，任何用户故事都不能开始。**

**⚠️ CRITICAL**: 本阶段是 US1–US4 的共同前置。

- [x] T005 [P] 扩展 `oryxos-core/src/main/java/io/oryxos/core/profile/Profile.java`：新增 `bootstrap.files`（`List<String>`，显式引用的 Bootstrap 文件）+ `settings` 便捷访问器 `maxIterations()`/`maxHistoryTurns()`/`processingTimeoutSeconds()`（默认值 10 / 20 / 60，缺失回退默认、非法值记 WARN）
- [x] T006 [P] 扩展 LLM 抽象：新增 `oryxos-core/src/main/java/io/oryxos/core/llm/LlmRequest.java`，并把 `oryxos-core/src/main/java/io/oryxos/core/llm/LlmClient.java` 的签名改为 `call(String sessionId, Profile profile, LlmRequest request)`（契约见 `contracts/llm-bridge.md` 第 2 节）
- [x] T007 [P] 新增 `oryxos-core/src/main/java/io/oryxos/core/audit/ToolInvocationAuditor.java` 与 `NoopToolInvocationAuditor.java`（位参签名，与既有 `LlmCallAuditor` 同口径）
- [x] T008 [P] 新增 `oryxos-core/src/main/java/io/oryxos/core/tool/ToolInvocationValidator.java` 与 `NoopToolInvocationValidator.java`（默认全放行；`validate(OryxTool, Map)` 不通过时抛异常）
- [x] T009 [P] 实现 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentContext.java`（`ThreadLocal<Profile>`：`set`/`current`/`clear`）
- [x] T010 [P] 实现 `oryxos-core/src/main/java/io/oryxos/core/context/ContextLoader.java`：`loadBootstrap(Profile)`、`loadSkill(String)`、`loadSystemContext(Profile)`（workspace = `.oryxos` 目录；解析规则见 `contracts/agent-runtime.md` 第 5 节）
- [x] T011 [P] 扩展 `oryxos-storage/src/main/java/io/oryxos/storage/entity/ToolInvocationEntity.java`（新增 `success`/`error_message`/`latency_ms`，`error` 更名为 `errorMessage`）并给 `oryxos-storage/src/main/java/io/oryxos/storage/repository/ToolInvocationRepository.java` 加 `findBySessionId`
- [x] T012 [P] 实现 `oryxos-storage/src/main/java/io/oryxos/storage/audit/JpaToolInvocationAuditor.java`（`@Component`；成功/失败都写；写失败记 ERROR 不抛出，与 `JpaLlmCallAuditor` 一致）
- [x] T013 [P] 实现 `oryxos-provider/src/main/java/io/oryxos/provider/client/ProviderServiceLlmClient.java`（core `LlmClient` → `ProviderService` 映射见 `contracts/llm-bridge.md` 第 3 节），并在 `oryxos-provider/src/main/java/io/oryxos/provider/config/ProviderAutoConfiguration.java` 注册 `LlmClient` Bean
- [x] T014 新增 `oryxos-core/src/main/java/io/oryxos/core/config/CoreAutoConfiguration.java`：装配 `SessionManager`、`ContextLoader`、`PromptBuilder`、`ToolRegistry`、`ToolExecutor`、`ReActLoop`、`AgentService`（仅用 spring-context 注解；`LlmClient` 用 `ObjectProvider` 注入，缺失时启动即报清晰错误）——**依赖 T019–T021、T031 的最终构造签名**
- [x] T015 在 `oryxos-core/src/main/java/io/oryxos/core/session/SessionManager.java` 落实会话持久化：`save(Session)` 写库、`get` 内存未命中时从库读回（经 core 的 `SessionStore` 端口落库，实现由 `oryxos-storage/JpaSessionStore` 提供（`messages_json` 序列化）；分层偏差见 plan.md「实现过程中的偏差与修正」#1）

**Checkpoint**: 契约、装配、落库路径就绪 → 用户故事可以开始。

---

## Phase 3: User Story 1 - 多步任务一次办成 (Priority: P1) 🎯 MVP

**Goal**: 一条用户消息驱动"推理—行动—观察"循环，模型自主调用工具并基于结果给出最终答复。

**Independent Test**: 给一个需要外部信息才能回答的问题，断言 Agent 发起了工具调用、工具结果被回填、最终返回不含工具请求的答复。

### Tests for User Story 1（先写、先失败）

- [x] T016 [P] [US1] `oryxos-core/src/test/java/io/oryxos/core/react/PromptBuilderTest.java`：四部分顺序正确；历史超 N 次完整往返被截断且工具消息不被拦腰截断
- [x] T017 [P] [US1] `oryxos-core/src/test/java/io/oryxos/core/react/ToolExecutorTest.java`：成功返回结果；工具未注册 → 失败结果；参数非法 JSON → 降级不崩；工具抛异常 → 转为失败结果不向上抛；多工具按序执行
- [x] T018 [P] [US1] `oryxos-core/src/test/java/io/oryxos/core/react/ReActLoopTest.java`：无工具调用一轮收尾；有工具调用则执行并回填后进入下一轮；每轮响应与工具结果都累积进会话

### Implementation for User Story 1

- [x] T019 [P] [US1] 实现 `oryxos-core/src/main/java/io/oryxos/core/react/PromptBuilder.java`：`build(Session, Profile, List<OryxTool>)` 产出 `LlmRequest`（四部分顺序、往返截断算法见 `research.md` D-03/D-04）
- [x] T020 [P] [US1] 实现 `oryxos-core/src/main/java/io/oryxos/core/react/ToolExecutor.java`：`execute(sessionId, call)` / `executeAll(sessionId, calls)`，顺序契约与错误降级见 `contracts/agent-runtime.md` 第 3 节
- [x] T021 [US1] 实现 `oryxos-core/src/main/java/io/oryxos/core/react/ReActLoop.java`：`run(sessionId, session, userMessage, profile)`，**先累积、再判工具调用**（`research.md` D-05），无工具调用且文本非空即收尾，文本与工具调用皆空时返回明确提示
- [x] T022 [US1] 运行 `mvn -pl oryxos-core -am test` 并让 T016–T018 全绿

**Checkpoint**: US1 可独立演示——"多步任务一次办成"跑通（MVP 达成）。

---

## Phase 4: User Story 2 - 卡住时能停下来 (Priority: P2)

**Goal**: 模型反复请求工具时不无限循环；单次处理有明确的轮数上限与整体耗时上限。

**Independent Test**: 让模型每轮都返回工具请求 → 断言恰好执行 10 轮后停止并返回可识别提示；构造超时 → 断言抛 `ProcessingTimeoutException`。

### Tests for User Story 2

- [x] T023 [US2] 扩展 `oryxos-core/src/test/java/io/oryxos/core/react/ReActLoopTest.java`：模型永不收敛 → `providerService/LlmClient` 恰好被调 10 次且返回文本含"已达到最大轮数"；Profile 覆盖轮数上限生效；超过整体耗时上限 → 抛 `ProcessingTimeoutException`

### Implementation for User Story 2

- [x] T024 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/react/ReActLoop.java` 实现最大轮数兜底：使用 `profile.maxIterations()`，达到上限返回"已达到最大轮数（N），已停止"（`StopReason.MAX_ITERATIONS`）
- [x] T025 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/react/ReActLoop.java` 实现整体耗时上限：处理开始计算 deadline（`profile.processingTimeoutSeconds()`），在步骤边界（每轮开始/工具调用前后/LLM 返回后）检查，超时抛 `ProcessingTimeoutException`（`research.md` D-06）
- [x] T026 [US2] 运行 `mvn -pl oryxos-core -am test "-Dtest=ReActLoopTest"` 并让 T023 全绿

**Checkpoint**: US2 完成——死循环与长时间挂死都被兜住。

---

## Phase 5: User Story 3 - 过程可追溯、会话可延续 (Priority: P3)

**Goal**: 每次工具调用都有审计（成功/失败都留痕），一次处理结束后会话历史被持久化，重启后可读。

**Independent Test**: 执行一次含工具调用的处理，查询 `tool_invocations` 与会话历史，验证两者都能还原关键信息（含失败场景）。

### Tests for User Story 3

- [x] T027 [US3] 扩展 `oryxos-core/src/test/java/io/oryxos/core/react/ToolExecutorTest.java`：成功写审计 `success=true`；失败写 `success=false` 且带原因；审计器抛异常时不阻断主流程（返回工具结果）
- [x] T028 [P] [US3] `oryxos-core/src/test/java/io/oryxos/core/agent/AgentServiceTest.java`：处理期间 `AgentContext.current()` 可取到当前 Profile；**处理抛异常时 `finally` 也清空**；处理结束后会话被持久化；Profile 不存在时抛 `AgentConfigurationException`
- [x] T029 [P] [US3] `oryxos-storage/src/test/java/io/oryxos/storage/repository/ToolInvocationRepositoryTest.java`：走 `db/schema.sql` 手工建表，`flush + clear` 后真往返读写，断言 `success` / `error_message` 两列真实存在且时间列可正确读回
- [x] T043 [P] [US3] `oryxos-storage/src/test/java/io/oryxos/storage/session/JpaSessionStoreTest.java`：走 `db/schema.sql` 手工建表，`flush + clear`（模拟重启）后读回，断言会话元数据、消息顺序与时间列；补齐 SC-004「重启后可读」的读回验证

### Implementation for User Story 3

- [x] T030 [US3] 在 `oryxos-core/src/main/java/io/oryxos/core/react/ToolExecutor.java` 接入前置校验与审计：查表 → `ToolInvocationValidator.validate` → 解析参数 → 执行 → 写审计（`sessionId` + 当前 `AgentContext` 的 profile 名）
- [x] T031 [US3] 实现 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java`：`process(sessionId, profileName, channel, userId, userMessage)`，Profile 解析 + `AgentContext` set/clear（try/finally）+ 成功后 `SessionManager.save` + 异常上抛（契约见 `contracts/agent-runtime.md` 第 4 节）
- [x] T032 [US3] 运行 `mvn test` 并让 T027–T029 全绿

**Checkpoint**: US3 完成——审计与持久化地基立起（Constitution VI）。

---

## Phase 6: User Story 4 - 人格与技能即改即生效 (Priority: P4)

**Goal**: 修改 Bootstrap / SKILL 文件后下一次提问立即生效；显式引用的文件缺失会明确报错，隐式缺失至少告警；系统提示末尾带当前日期时间。

**Independent Test**: 改文件后再次组装输入 → 内容已更新；引用不存在的文件 → 报错；删除默认 Bootstrap 文件 → 仅 WARN 且继续。

### Tests for User Story 4

- [x] T033 [P] [US4] `oryxos-core/src/test/java/io/oryxos/core/context/ContextLoaderTest.java`：改文件后下一次 `loadSystemContext` 立即读到新内容（无缓存回归）；显式引用的 SKILL 缺失 → 抛 `ContextLoadException`；未配置 `bootstrap.files` 时默认三件套缺失 → 仅 WARN 且继续
- [x] T034 [P] [US4] 扩展 `oryxos-core/src/test/java/io/oryxos/core/react/PromptBuilderTest.java`：system prompt 末尾包含当前日期时间；长期记忆作为独立部分且位于会话历史之前

### Implementation for User Story 4

- [x] T035 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/context/ContextLoader.java` 落实策略：无缓存、显式引用缺失报错、默认三件套 `AGENTS.md`/`SOUL.md`/`USER.md` 缺失仅 WARN（`research.md` D-02）
- [x] T036 [US4] 在 `oryxos-core/src/main/java/io/oryxos/core/react/PromptBuilder.java` 接入 `ContextLoader` 输出并追加当前日期时间（`yyyy-MM-dd HH:mm:ss z`）
- [x] T037 [US4] 运行 `mvn test` 并让 T033–T034 全绿

**Checkpoint**: US4 完成——人格/技能的调优闭环成立。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 结构化日志、真实模型验证、配置示例与文档回填。

- [x] T038 [P] 落实结构化日志（FR-021）：在 `oryxos-core/src/main/java/io/oryxos/core/react/ReActLoop.java`、`oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java`、`oryxos-core/src/main/java/io/oryxos/core/react/ToolExecutor.java` 按 `research.md` D-11 的四处落点补日志（处理开始/结束含停止原因与耗时、每轮迭代、每次工具调用、异常与超时；敏感信息不入日志）
- [x] T039 [P] 新增手动集成用例 `oryxos-boot/src/test/java/io/oryxos/ReactLoopSmokeIT.java`：注册本地假工具（如 `get_weather`）+ 真实 Provider 驱动循环，断言工具被调用、会话含 `assistant`/`tool` 消息、最终答复不含工具请求（`@Tag("integration")`，默认跳过）
- [x] T040 [P] 更新 `.oryxos/profiles/default.yaml`：补充 `bootstrap.files` 与 `settings`（`max_iterations` / `max_history_turns` / `processing_timeout_seconds`）示例
- [x] T044 [P] `oryxos-core/src/test/java/io/oryxos/core/config/CoreAutoConfigurationTest.java`：断言 core 引擎层必需 Bean 齐备（`LlmClient`/`ProfileRegistry` 就绪时可装配成功），缺失时给出清晰错误——补齐 Phase 2 装配的测试缺口
- [x] T045 [P] `oryxos-boot/src/test/java/io/oryxos/ReactLoopRealRunIT.java`：真实模型驱动一次完整 ReAct 循环，且**不覆盖数据源**（与运行中的应用共用 `.oryxos/oryxos.db`），断言 `llm_calls` 与 `tool_invocations` 均可按 sessionId 查回，并打印真实库文件路径
- [x] T046 [US3] core 请求级审计契约：新增 `audit/AgentRunAuditor.java`、`audit/NoopAgentRunAuditor.java`；扩展 `react/StopReason.java`（+配置错误/内部错误）、`react/ProcessingTimeoutException.java`（携带已完成轮数/工具次数/token）、`agent/AgentContext.java`（携带 runId）；新增 `react/ReActResult.java`、`react/ModelCallException.java`；`react/ReActLoop.java` 抽出 `runResult` 返回结局元数据
- [x] T047 [US3] `oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java`：接入请求级审计（成功/达上限/空响应/超时/模型失败/配置错误/内部错误都落库）、`finally` 中持久化会话（修正 FR-009 失败路径偏差）
- [x] T048 [US3] storage 请求级审计：`db/schema.sql` 与 `db/migration/2026-09-18-agent-runs-and-run-id.sql` 建 `agent_runs` 并给明细补 `run_id`；新增 `entity/AgentRunEntity.java`、`repository/AgentRunRepository.java`、`audit/JpaAgentRunAuditor.java`（RUNNING → FINISHED，缺 started 时补写不丢）；`JpaLlmCallAuditor`/`JpaToolInvocationAuditor` 从处理上下文回填 `run_id`
- [x] T049 [US3] 测试补齐：新增 `AgentRunRepositoryTest`（请求级两阶段读写 + 明细按 runId 关联 + 无上下文照常落库）；`AgentServiceTest` 扩到 14 例（七种终态 + 超时路径落库 + 审计自身异常不阻断 + MDC 的 runId 与落库一致）；`ReActLoopTest` 扩到 12 例（`runResult` 元数据、超时异常携带进度）；存储侧测试库改为上下文启动前删库，杜绝"老库缺新列"
- [x] T050 文档同步：`spec.md` 补 FR-022 / FR-023 / SC-011、澄清第 6 条、US3 场景 5 与 Key Entities，并修正 SC-004 的过度声称；`plan.md`、`quickstart.md` 同步
- [x] T041 全量回归：`mvn test` 全绿（91 个测试、10 模块 BUILD SUCCESS），并按 `quickstart.md` 第 2 节逐条人工确认
- [x] T042 回填特性文档：把 `specs/002-react-loop/tasks.md` 的任务勾选为完成、在 `specs/002-react-loop/plan.md` 记录实际产出物与偏差修正、更新 `spec.md` 的验收标准状态

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**：无依赖，可立即开始
- **Foundational (Phase 2)**：依赖 Setup 完成；**阻塞所有用户故事**
- **User Stories (Phase 3–6)**：全部依赖 Foundational 完成
- **Polish (Phase 7)**：依赖所有目标用户故事完成

### User Story Dependencies

| 故事 | 依赖 | 说明 |
|------|------|------|
| US1 (P1) | Foundational | 独立可测；是 MVP |
| US2 (P2) | Foundational + **US1** | 都在 `ReActLoop.java` 上工作，需在 US1 之后串行 |
| US3 (P3) | Foundational + **US1** | T030 修改 US1 建立的 `ToolExecutor.java` |
| US4 (P4) | Foundational + **US1** | T036 修改 US1 建立的 `PromptBuilder.java` |

### Within Each User Story

- 测试必须先写并**失败**，再实现（spec 要求 harness）
- 类型/契约 → 服务实现 → 装配 → 验证
- 每个故事的 Checkpoint 必须独立验证通过后再进入下一个

### 文件冲突提示（并行前必读）

同一文件被多个故事触碰，必须串行：

- `ReActLoop.java`：T021（US1）→ T024/T025（US2）→ T038（Polish）
- `ToolExecutor.java`：T020（US1）→ T030（US3）→ T038（Polish）
- `PromptBuilder.java`：T019（US1）→ T036（US4）
- `ReActLoopTest.java`：T018（US1）→ T023（US2）
- `PromptBuilderTest.java`：T016（US1）→ T034（US4）

---

## Parallel Example: User Story 1

```bash
# 并行写出 US1 的三个 harness 测试（不同文件，先失败）
Task: "PromptBuilderTest in oryxos-core/src/test/java/io/oryxos/core/react/PromptBuilderTest.java"
Task: "ToolExecutorTest in oryxos-core/src/test/java/io/oryxos/core/react/ToolExecutorTest.java"
Task: "ReActLoopTest in oryxos-core/src/test/java/io/oryxos/core/react/ReActLoopTest.java"

# 再并行实现 US1 中互不依赖的两个组件
Task: "PromptBuilder in oryxos-core/src/main/java/io/oryxos/core/react/PromptBuilder.java"
Task: "ToolExecutor in oryxos-core/src/main/java/io/oryxos/core/react/ToolExecutor.java"
# 之后串行实现 ReActLoop（依赖上述二者）
```

---

## Implementation Strategy

### MVP First（仅 User Story 1）

1. 完成 Phase 1 Setup
2. 完成 Phase 2 Foundational（**关键**：阻塞所有故事）
3. 完成 Phase 3 US1
4. **停下来验证**：`mvn -pl oryxos-core -am test` → US1 三个测试类全绿
5. 此时已可演示"多步任务一次办成"

### Incremental Delivery

1. Setup + Foundational → 地基就绪
2. + US1 → 独立验证 → 可演示（MVP）
3. + US2 → 独立验证 → 死循环/超时兜底
4. + US3 → 独立验证 → 审计与持久化
5. + US4 → 独立验证 → 上下文热更新
6. + Polish → 日志、真实模型验证、文档回填

### 真实模型验证（可选，手动）

`ReactLoopSmokeIT` 需要 `DEEPSEEK_API_KEY`，命令见 `quickstart.md` 第 3 节；不设置环境变量时该用例自动跳过，不影响 `mvn test`。

---

## Notes

- `[P]` = 不同文件、无未完成依赖，可并行
- `[Story]` 标签用于把任务回溯到 spec 的用户故事
- 每个用户故事都应能独立完成与独立验证
- 实现前先确认对应测试处于**失败**状态
- 提交按"每个任务或逻辑分组"进行
- 不要跨故事引入依赖，否则故事独立性被破坏
- 核心阶段范围外（并行工具、Agent 委托、流式、上下文压缩、fallback、向量检索）一律不做
- 另有两项**明确后置**（已评审确认，不属本特性缺口）：① 具体通道接入（`AgentApiController` / `oryxos chat` 仍为占位）；② 内置工具的启动装配（`ToolRegistry` 启动时为空）。详见 plan.md「范围外（明确后置，本特性不做）」

---

## Task Management

- **Total Tasks**: 50
- **Completed**: 50
- **Pending**: 0

**实现说明**：US1–US4 的核心类（`PromptBuilder`、`ToolExecutor`、`ReActLoop`、`AgentService`）与 Phase 2 的装配（`CoreAutoConfiguration`）存在编译期互相依赖，因此按"批次落地"实现（Phase 2 装配 → core 引擎四个类 → harness 测试），而非逐任务独立提交；测试与实现一一对应，任务粒度与验收点未变。T043/T044 为 `/speckit.analyze` 复核后补齐的验收缺口（会话"重启后可读"读回路径、core 引擎层装配），已随本次修订落地。

*产出物清单、实现偏差与验证记录见 [plan.md](./plan.md) 的「实际交付物（产出记录）」「实现过程中的偏差与修正」「验证记录」三节。*
