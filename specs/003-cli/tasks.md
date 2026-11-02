---

description: "CLI 命令行入口与会话管理实施任务"
---

# Tasks: CLI 命令行入口与会话管理

**Input**: Design documents from `/specs/003-cli/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/`、`quickstart.md`

**Tests**: 本规格明确要求 Session、CLI Channel、命令帮助、轻重命令分流和端到端测试；测试任务包含在各用户故事中，并应先于实现编写。

**Organization**: Tasks are grouped by user story so each story can be implemented and tested independently.

**Status legend**: `[X]` 已完成并有证据（自动化测试或手工验证）；`[ ]` 未完成，下方 **进度** 说明已完成部分与剩余范围。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 确认既有模块、构建入口和功能测试目录，不新增 Maven 模块或第三方依赖。

- [X] T001 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/session/`、`oryxos-storage/src/test/java/io/oryxos/storage/`、`oryxos-channel-cli/src/test/java/io/oryxos/channel/cli/` 和 `oryxos-cli/src/test/java/io/oryxos/cli/` 建立 CLI 特性测试目录
- [X] T002 [P] 在 `oryxos-cli/pom.xml`、`oryxos-channel-cli/pom.xml` 和 `oryxos-storage/pom.xml` 核对 JUnit 5、Mockito、AssertJ、Spring Boot Test 和 SQLite 测试依赖，缺失时仅补充既有技术栈依赖
- [X] T003 [P] 在 `specs/003-cli/quickstart.md` 固化 JDK 21、Maven 3.9.9、工作区路径和默认测试命令，作为实现验收入口

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 完成所有用户故事依赖的 Session 端口、持久化模型、工作区解析和错误边界。

**CRITICAL**: 本阶段完成前不得开始依赖共享 Session 的用户故事实现。

- [X] T004 [P] 为 `oryxos-core/src/main/java/io/oryxos/core/session/SessionStore.java` 定义按 `sessionId`、`channel + userId + profileName` 查询、保存和安全列表所需的 Core 端口语义
- [X] T005 [P] 为 `oryxos-core/src/main/java/io/oryxos/core/session/Session.java` 补齐消息角色、顺序、状态、创建时间和最后活跃时间的稳定访问与恢复语义
- [X] T006 为 `oryxos-core/src/main/java/io/oryxos/core/session/SessionManager.java` 实现 `getOrCreate(channel, userId, profileName)`，保证内存索引优先、Store 查询其次、创建最后，并集中生成 Session ID
- [X] T007 [P] 为 `oryxos-storage/src/main/java/io/oryxos/storage/entity/SessionEntity.java` 增加或确认 `channel`、`user_id`、`profile_name`、状态、时间戳和 `messages_json` 的持久化映射
- [X] T008 [P] 为 `oryxos-storage/src/main/java/io/oryxos/storage/repository/SessionRepository.java` 增加活动 Session 的三元组查询和列表查询，并为业务身份建立唯一性保护
- [X] T009 为 `oryxos-storage/src/main/java/io/oryxos/storage/session/JpaSessionStore.java` 实现 Session 与 Entity 的完整双向转换、消息 JSON 顺序保持和持久化异常传播
- [ ] T010 [P] 更新 `oryxos-storage/src/main/resources/db/schema.sql` 和 `oryxos-storage/src/main/resources/db/migration/` 下的迁移脚本，支持 Session 三元组唯一性且不破坏已有历史
      **进度**：`schema.sql` 已新增 `uq_sessions_active_identity`（`(channel, user_id, profile_name) WHERE status = 'active'` 的部分唯一索引）与 `idx_sessions_identity`；因使用 `IF NOT EXISTS`，对新库和既有库启动时都会补齐。
      **剩余**：未提供"既有库已存在重复活动 Session"时的去重清理迁移脚本（该场景下建唯一索引会失败）。`db/migration/` 现有 3 个脚本均属审计表，不涉及 sessions。
- [X] T011 [P] 在 `oryxos-cli/src/main/java/io/oryxos/cli/` 增加共享工作区路径解析和脱敏错误边界，统一默认 `.oryxos/`、stderr 定位信息和非成功结果
- [X] T012 [P] 在 `oryxos-cli/src/main/java/io/oryxos/cli/` 增加共享轻命令/重命令运行模式判定接口，确保轻命令不构造完整 Agent 上下文
- [X] T013 [P] 在 `oryxos-cli/src/test/java/io/oryxos/cli/` 增加运行模式判定测试，验证轻命令和重命令的分流边界
      **说明**：文件名为 `CommandModesTest.java`（覆盖轻/重判定、常驻判定、Web 判定，以及"无参数/`--spring.*` 不算 CLI 调用"）。

**Checkpoint**: Core Session 端口、SQLite 适配、工作区解析和错误边界可被用户故事复用。

---

## Phase 3: User Story 1 - 初始化并查看 OryxOS 工作区 (Priority: P1) 🎯 MVP

**Goal**: 用户可在空目录初始化 `.oryxos` 工作区，并使用状态、Profile、Provider、Tool、Session 查询命令，不依赖模型服务。

**Independent Test**: 在临时空目录执行 `init`，重复执行确认不覆盖；随后执行 `status`、`profile list`、`provider list`、`tool list`、`session list`，验证成功、空结果和缺失工作区错误。

### Tests for User Story 1

- [X] T014 [P] [US1] 编写 `oryxos-cli/src/test/java/io/oryxos/cli/InitCommandTest.java`，覆盖空目录初始化、默认 Profile、Bootstrap/Memory/Skill/Session/logs 目录和重复初始化不覆盖
- [X] T015 [P] [US1] 编写 `oryxos-cli/src/test/java/io/oryxos/cli/QueryCommandTest.java`，覆盖 `status`、`profile list`、`provider list`、`tool list`、`session list` 的有数据、空数据和未初始化工作区场景
- [X] T016 [P] [US1] 编写 `oryxos-cli/src/test/java/io/oryxos/cli/CommandHelpTest.java`，验证根命令和 12 个核心命令的用途、参数、默认值或示例可发现
      **说明**：12 个命令 = 5 个顶层（`init`/`status`/`chat`/`serve`/`gateway`）+ 4 个 `profile` 子命令 + 3 个列表命令（`provider list`/`tool list`/`session list`）。

### Implementation for User Story 1

- [X] T017 [P] [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/` 实现共享 `WorkspaceLayout`/等价工作区服务，统一 `.oryxos` 子目录、Bootstrap 文件、`MEMORY.md` 和数据库路径
- [X] T018 [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java` 实现 `init`，创建缺失文件和默认 Profile，重复执行时保护已有配置与 Session 数据
- [X] T019 [P] [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java` 实现 `status`，输出工作区、Profile、Session 和运行状态摘要，并对未初始化目录给出路径诊断
- [X] T020 [P] [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/ProfileCommand.java` 实现 `profile list`，输出 Profile 名称和可读状态，空目录返回空结果说明
- [X] T021 [P] [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/ProviderCommand.java` 实现 `provider list`，列出可识别 Provider 或安全的空结果，不输出凭证
- [X] T022 [P] [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/ToolCommand.java` 实现 `tool list`，列出可识别 Tool 或安全的空结果，不执行 Tool
- [ ] T023 [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/SessionCommand.java` 实现 `session list`，通过 `SessionStore` 输出 Session 摘要和可选过滤结果，不输出完整提示词或敏感配置
      **进度**：`SessionStore.list()` 端口与 `JpaSessionStore.list()`（按 `lastActiveAt` 倒序）已就绪；命令侧目前只校验工作区并提示存储位置。
      **剩余**：命令层接入 `SessionStore`，输出 sessionId / profile / channel / user / status / lastActiveAt 摘要与过滤。
- [X] T024 [US1] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java` 补齐根命令和所有子命令的 Picocli 帮助、非法参数处理和非成功退出结果

**Checkpoint**: 空目录可完成初始化和轻量查询；所有轻命令无需 Provider API key 或完整 Agent 运行时。

---

## Phase 4: User Story 2 - 通过命令行完成多轮 Agent 对话 (Priority: P1)

**Goal**: `chat` 通过统一 Agent 服务完成指定 Profile 的多轮对话，并复用同一 CLI Session，支持 `/quit`、EOF、空行和失败传播。

**Independent Test**: 使用假的 `AgentService`/`LlmClient` 启动 `CliChannel`，输入两条消息、空行、`/quit` 和 EOF，验证委托顺序、Profile、Session 复用、输出和错误状态。

### Tests for User Story 2

- [X] T025 [P] [US2] 编写 `oryxos-core/src/test/java/io/oryxos/core/session/SessionManagerTest.java`，覆盖相同 `channel + userId + profileName` 返回同一 Session，以及三元组任一字段变化产生隔离 Session
- [ ] T026 [P] [US2] 编写 `oryxos-storage/src/test/java/io/oryxos/storage/session/JpaSessionStoreTest.java`，覆盖消息角色/顺序/元数据 JSON 读回、新运行上下文恢复、Profile 删除后历史保留和持久化失败可见
      **进度**：已覆盖消息角色/顺序/时间戳 JSON 读回、`flush + clear` 后仅从库读回（模拟重启）、不存在的会话返回空。测试库改为每次运行使用唯一文件名，避免 Windows 下上轮 JVM 仍持有 SQLite 文件句柄导致整个测试类报错。
      **剩余**：Profile 删除后历史保留、持久化失败可见两条断言。
- [X] T027 [P] [US2] 编写 `oryxos-channel-cli/src/test/java/io/oryxos/channel/cli/CliChannelTest.java`，覆盖两轮输入、空行、`/quit`、EOF、指定 Profile、AgentService 委托和错误输出
- [ ] T028 [P] [US2] 编写 `oryxos-cli/src/test/java/io/oryxos/cli/ChatCommandTest.java`，验证 `--profile` 传递、默认 `default`、重命令运行时接线和非成功退出结果
      **进度**：`CommandModesTest` 覆盖 `chat --profile default` 的重命令判定；`RuntimeCommandTest` 覆盖"无运行时注入时 chat/serve/gateway 非零退出"。
      **剩余**：断言 `--profile` 的值确实被传入 `RuntimeLauncher.chatChannel()`（需要可注入的替身启动器）。

### Implementation for User Story 2

- [X] T029 [US2] 在 `oryxos-core/src/main/java/io/oryxos/core/session/SessionManager.java` 完成并发安全的三元组 `getOrCreate`、保存和恢复流程，避免同一身份重复创建活动 Session
- [X] T030 [US2] 在 `oryxos-storage/src/main/java/io/oryxos/storage/session/JpaSessionStore.java` 完成三元组查询、保存更新、列表摘要和持久化异常向调用方传播
- [X] T031 [US2] 在 `oryxos-channel-cli/src/main/java/io/oryxos/channel/cli/CliChannel.java` 将 echo 占位替换为 `AgentService` 调用，处理提示符、空行、EOF、`/quit`、最终回复和脱敏错误
- [X] T032 [US2] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java` 或现有 ChatCommand 所在文件接收 `--profile`，默认使用 `default`，并通过运行上下文获取 `AgentService` 与 `SessionManager`
- [X] T033 [US2] 在 `oryxos-cli/src/main/java/io/oryxos/cli/` 实现共享 `RuntimeLauncher`/等价重命令启动协调器，装配 ProfileRegistry、LlmClient、AgentService、SessionStore、审计、JPA Repository 和 Entity
- [X] T034 [US2] 在 `oryxos-cli/src/main/java/io/oryxos/cli/` 将 `ChatCommand` 接入 `RuntimeLauncher`，禁止命令层自行构造第二套 ReAct、Provider、Tool 或 Memory 逻辑
- [ ] T035 [US2] 在 `oryxos-storage/src/main/java/io/oryxos/storage/session/JpaSessionStore.java` 和 `oryxos-channel-cli/src/main/java/io/oryxos/channel/cli/CliChannel.java` 保证 Agent 原始错误优先、保存错误可见，并返回非成功结果
      **进度**：`JpaSessionStore` 序列化/反序列化失败改为抛 `IllegalStateException`（原先吞异常）；`CliChannelTest` 覆盖"AgentService 抛错时输出脱敏错误、不伪造回复"。
      **剩余**："Agent 失败 + 同时保存失败"的组合场景未断言保存错误可被观测。

**Checkpoint**: `chat` 可使用默认或指定 Profile 完成至少两轮对话，重启后能恢复同一 Session，失败不会被 echo 或伪造回复掩盖。

---

## Phase 5: User Story 3 - 管理 Profile 并查看详情 (Priority: P2)

**Goal**: 用户可创建、列出、查看和删除 Profile，同时保留相关 Session 和审计历史。

**Independent Test**: 在已初始化工作区创建 `demo` Profile，执行 show/list，尝试重复创建和不存在操作，再删除并验证 Profile 消失但 Session 数据仍在。

### Tests for User Story 3

- [X] T036 [P] [US3] 编写 `oryxos-cli/src/test/java/io/oryxos/cli/ProfileCommandTest.java`，覆盖 create/list/show/delete 成功流程、重复名称、不存在名称、非法名称和默认 Profile 保护
- [X] T037 [P] [US3] 在 `oryxos-cli/src/test/java/io/oryxos/cli/ProfileCommandTest.java` 增加敏感配置脱敏断言，确保 API key、凭证和完整系统提示词不出现在 stdout/stderr
- [ ] T038 [P] [US3] 在 `oryxos-cli/src/test/java/io/oryxos/cli/ProfileSessionRetentionTest.java` 验证删除 Profile 不级联删除 `oryxos-storage` 中的 Session、LLM 调用和 Tool 调用历史
      **进度**：`profile delete` 实现只删配置文件、不触碰任何表；`SessionManager.getOrCreate` 的恢复路径与 `sessions` 表按三元组查询，不依赖 Profile 是否存在。
      **剩余**：缺少"先写会话 → 删 Profile → 断言历史仍在"的自动化回归。

### Implementation for User Story 3

- [X] T039 [US3] 在 `oryxos-cli/src/main/java/io/oryxos/cli/ProfileCommand.java` 实现 `profile create <name>`，生成可编辑的 Profile YAML 模板并拒绝重复或非法名称
- [X] T040 [US3] 在 `oryxos-cli/src/main/java/io/oryxos/cli/ProfileCommand.java` 实现 `profile show <name>`，展示脱敏后的 identity、Provider、Tool、Channel、Bootstrap 和 settings
- [X] T041 [US3] 在 `oryxos-cli/src/main/java/io/oryxos/cli/ProfileCommand.java` 实现 `profile delete <name>`，保护默认 Profile，删除配置文件或标记删除但不触碰历史数据
- [X] T042 [US3] 在 `oryxos-cli/src/main/java/io/oryxos/cli/ProfileCommand.java` 统一处理工作区缺失、YAML 非法、文件不可写、名称冲突和不存在资源的诊断及退出状态

**Checkpoint**: Profile 生命周期可独立使用；错误和详情输出可读且脱敏；删除 Profile 不影响历史 Session。

---

## Phase 6: User Story 4 - 以不同模式运行 OryxOS (Priority: P2)

**Goal**: `serve` 和 `gateway` 与 `chat` 共享 Profile、Session、Agent 和 Storage 运行上下文，不产生入口专属逻辑分叉。

**Independent Test**: 使用测试配置启动 `serve --help`、`gateway --help` 和可启动流程，验证缺失/非法配置在启动前失败，完整运行模式能访问共享持久化和审计组件。

### Tests for User Story 4

- [ ] T043 [P] [US4] 编写 `oryxos-cli/src/test/java/io/oryxos/cli/RuntimeLauncherTest.java`，验证重命令装配 ProfileRegistry、AgentService、SessionStore、审计、JPA Repository 和 Entity
      **进度**：`oryxos-boot` 的 `SpringRuntimeLauncherTest` 验证 `chatChannel()` 复用注入的 `AgentService`/`SessionManager`；启动日志实测 `Found 4 JPA repository interfaces` 与 `SessionManager 装配完成（持久化: JpaSessionStore）`。
      **剩余**：缺一个断言完整装配（ProfileRegistry/审计/JPA）的自动化测试。
- [X] T044 [P] [US4] 编写 `oryxos-cli/src/test/java/io/oryxos/cli/ServeCommandTest.java`，覆盖默认端口、端口覆盖、非法端口、缺失配置和启动失败的非成功结果
      **说明**：实际落在 `RuntimeCommandTest.java`（`serve` 非法端口 0 / 65536 非零退出；无运行时注入时非零退出）。
- [X] T045 [P] [US4] 编写 `oryxos-cli/src/test/java/io/oryxos/cli/GatewayCommandTest.java`，覆盖常驻启动边界、共享 Profile/Session 服务、缺失配置和启动前诊断
      **说明**：同 `RuntimeCommandTest.java`。
- [ ] T046 [P] [US4] 编写 `oryxos-boot/src/test/java/io/oryxos/CliRuntimeSmokeIT.java`，使用假 Provider/临时 SQLite 验证 chat、serve、gateway 共享工作区和 Session 数据来源
      **进度**：已用真实进程 + 临时工作区手工验证（`init` → `chat` → 会话落 `.oryxos/oryxos.db`）；`ReactLoopSmokeIT` 等既有 IT 覆盖 Agent 链路。
      **剩余**：缺一个把三个入口串起来、断言共享 Session 数据来源的自动化 IT。

### Implementation for User Story 4

- [X] T047 [US4] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java` 或现有 ServeCommand 所在文件实现 `serve` 参数解析、默认端口和端口覆盖，并委托既有 Web 启动边界
- [X] T048 [US4] 在 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java` 或现有 GatewayCommand 所在文件实现 `gateway` 启动参数校验和常驻运行边界，复用统一 Agent/Session 服务
- [X] T049 [US4] 在 `oryxos-cli/src/main/java/io/oryxos/cli/` 完成 `serve`、`gateway` 与 `RuntimeLauncher` 的共享上下文接线，禁止创建入口专属 Agent 处理逻辑
- [X] T050 [US4] 在 `oryxos-boot/src/main/java/io/oryxos/` 和必要的 `oryxos-web/src/main/java/` 启动配置中修正跨模块组件、Repository、Entity 和 Storage 扫描，使重命令可持久化 Session 并写入审计
- [X] T051 [US4] 在 `oryxos-cli/src/main/java/io/oryxos/cli/` 处理重命令启动失败、非法端口、损坏 Profile 和不可写工作区，避免进入半可用状态并输出脱敏诊断

**Checkpoint**: `chat`、`serve`、`gateway` 使用同一套 Profile/Session/Agent/Storage 约定；启动错误在进入运行态前可识别。

### serve / gateway 已实现边界（诚实标注）

| 命令 | 已实现 | 未实现（后续能力） |
|---|---|---|
| `chat` | 完整：Console 交互、`/quit`、多轮、会话持久化与重启恢复、失败传播 | — |
| `serve` | 以 `WebApplicationType.SERVLET` 启动 Spring，复用 `oryxos-web` 既有 REST 控制器；`--port` 通过 `--server.port` 覆盖；常驻不退出 | 无新增 REST 面；`SpringRuntimeLauncher.serve()` 只做日志确认，未做端口冲突/启动失败的前置探测 |
| `gateway` | 以 `WebApplicationType.NONE` 启动共享运行时并常驻（`Thread.currentThread().join()`）；复用同一 `AgentService`/`SessionManager` | 未挂载多渠道、无路由与 Channel 管理；IM Channel 属扩展阶段 |

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 完成跨故事验证、文档一致性和最终质量门禁。

- [ ] T052 [P] 在 `oryxos-cli/src/test/java/io/oryxos/cli/CliEndToEndTest.java` 增加无网络依赖的端到端流程：初始化、Profile 管理、双轮聊天、Session 重启恢复、轻重命令分流和帮助
      **进度**：上述流程已由**手工端到端**验证通过（真实进程 + 真实 DeepSeek：`init` → 两轮对话 → Ctrl+C → 重启 → 第三轮带上下文）。
      **剩余**：未固化为自动化测试。
- [ ] T053 [P] 在 `oryxos-cli/src/test/java/io/oryxos/cli/RedactionTest.java` 覆盖典型模型、工具、配置和持久化错误，确认 stdout/stderr/logs 不包含 API key、凭证或完整系统提示词
      **进度**：`ProfileCommandTest` 断言 `profile show` 不输出 `apiKey`/`password`/`systemPrompt`；`QueryCommandTest` 断言 `profile list` 不泄漏凭证；`ProviderModelFactory` 日志对 key 做 `sk-****530b` 形式脱敏。
      **剩余**：未覆盖模型/工具/持久化错误路径的脱敏断言。
- [ ] T054 [P] 在 `oryxos-core/src/test/java/io/oryxos/core/session/SessionManagerTest.java` 和 `oryxos-storage/src/test/java/io/oryxos/storage/session/JpaSessionStoreTest.java` 增加并发获取、重复保存和重启恢复回归场景
      **进度**：`SessionManagerTest` 覆盖"有持久化端口时按业务身份恢复"；`JpaSessionStoreTest` 覆盖"读回后接续追加并再次持久化（更新而非新增）"。
      **剩余**：并发 `getOrCreate`、重复 `save` 的专项回归。
- [X] T055 更新 `specs/003-cli/quickstart.md` 与 `specs/003-cli/contracts/`，使命令参数、SessionStore 行为和实际实现一致
      **完成内容**：`quickstart.md` 补运行方式、Windows 中文编码、工作区结构、`bin/oryxos.cmd` 维护约束、实测记录与测试覆盖状态表；`contracts/cli.md` 更新为已实现状态并补「已实现边界」表；`contracts/session-store.md` 增加状态、实现位置、逐条验证结果与实现备注。
- [X] T056 更新 `g:/code/ORYXOS/docs/` 下与 CLI 运行方式相关的交付说明，明确 `serve/gateway` 已实现范围和后置能力边界
- [X] T057 使用 JDK 21 和固定 Maven 3.9.9 执行 `g:/code/ORYXOS` 根目录全量测试，并修复 CLI 特性引入的回归
- [ ] T058 执行 `/speckit.analyze`，根据 `spec.md`、`plan.md`、`tasks.md` 和实现差异更新规格或任务记录
      **剩余**：待用户执行；本文件与 `plan.md` 已先行同步实现差异。

---

## Phase 8: 实现期新增任务（原计划未覆盖）

**Purpose**: 实现过程中暴露出的、原计划没有预见的问题；均已完成并有测试或实测证据。

- [X] T059 新增 `io.oryxos.OryxosLauncher` 作为单二进制进程入口（无参数退化为普通 Spring 启动；带命令名时按轻重分流），新增 `io.oryxos.cli.CommandModes`/`CommandMode` 分流判定，`oryxos-boot` 的 `mainClass` 切到启动入口
      **背景**：原计划假设 `OryxOsCli` 直接作为 `main`，但那样重命令无法拿到 Spring 上下文；且无参数启动 Web 服务的既有行为必须保留。
- [X] T060 修正 `oryxos init`/`profile create` 生成的默认 Profile 模板：`provider` 改为对象、`channels` 改为对象列表、文件后缀统一 `.yaml`，并补齐 `AGENTS.md`/`SOUL.md`/`USER.md` 等 Bootstrap 文件；新增 ProfileLoader 解析回归测试
      **背景**：原模板 `provider: default` 与 `Profile.ProviderConfig` 结构不符，导致 `chat` 启动即报 `Profile 不存在`。回归测试直接调用真实 `ProfileLoader` 断言能解析。
- [X] T061 修复 Windows 中文乱码（两条独立路径）：① `CliChannel` 交互式终端改用 `System.console()`（Windows 底层 ReadConsoleW/WriteConsoleW 宽字符 API，不受控制台代码页影响），重定向场景保留 UTF-8 流式读写；② 新增 `io.oryxos.boot.ConsoleEncoding`，启动时把 `System.out`/`System.err` 接管到控制台宽字符通道（必须早于 Spring，否则 Logback 会先捕获旧流），并在 `application.yaml` 固定 `logging.charset.console=UTF-8`；无控制台时全部为 no-op
      **背景**：`System.out.println(String)` 走 `PrintStream` 自身 charset（中文系统取 ANSI 代码页 GBK），而 Logback 按字节写 UTF-8，两条路径表现不一致。附回归测试 `ConsoleEncodingTest` 守住"无控制台不得干预"。
- [X] T062 新增 `bin/oryxos.cmd` 包装脚本，把 `java -jar <fat JAR>` 固化为 `oryxos` 命令：固定 JDK 21（系统默认 JAVA_HOME 指向 JDK 8）、用 `%~dp0` 解析仓库内 JAR 路径、原样转发参数与退出码，支持 `ORYXOS_JAVA_HOME`/`ORYXOS_JAR`/`ORYXOS_JAVA_OPTS` 覆盖
      **约束**：脚本必须保持纯 ASCII + CRLF + 无 BOM。`.cmd` 由 cmd.exe 按控制台代码页逐行解析，混入非 ASCII 后会把一行拆成几截当命令执行（实测症状：`'JDK' 不是内部或外部命令`）。

---

## 交付完成记录

### 命令清单（12 个）

| 类型 | 命令 |
|---|---|
| 顶层（5） | `init`、`status`、`chat`、`serve`、`gateway` |
| Profile 子命令（4） | `profile list`、`profile create <name>`、`profile show <name>`、`profile delete <name>` |
| 列表（3） | `provider list`、`tool list`、`session list` |

分流规则：`chat`/`serve`/`gateway` 为重命令（启 Spring），其余为轻命令（不启 Spring，毫秒级返回，不需要 API key）。

### 关键实现位置

| 关注点 | 位置 |
|---|---|
| 进程入口 / 轻重分流 | `oryxos-boot` 的 `OryxosLauncher`；`oryxos-cli` 的 `CommandModes`/`CommandMode` |
| Spring 运行时装配 | `oryxos-boot` 的 `OryxosApplication`（`@EnableJpaRepositories` + `@EntityScan`）、`SpringRuntimeLauncher` |
| 控制台编码 | `oryxos-boot` 的 `ConsoleEncoding`；`CliChannel` 的 `System.console()` 分支 |
| 命令注册与轻命令 | `oryxos-cli` 的 `OryxOsCli`、`WorkspaceLayout`、`ProfileTemplates`、`ProfileCommand`、`ProviderCommand`、`ToolCommand`、`SessionCommand` |
| CLI 交互协议 | `oryxos-channel-cli` 的 `CliChannel` |
| 会话身份与生命周期 | `oryxos-core` 的 `SessionManager`、`SessionStore`、`Session` |
| 会话持久化 | `oryxos-storage` 的 `JpaSessionStore`、`SessionRepository`、`SessionEntity`、`db/schema.sql` |
| 启动包装 | `bin/oryxos.cmd` |

### 验证证据

| 验证项 | 方式 | 结果 |
|---|---|---|
| 全量构建与测试 | JDK 21 + Maven 3.9.9，仓库根 `mvn test` | 10 个模块 BUILD SUCCESS |
| 轻重命令分流 | `CommandModesTest`（4 例） | 通过 |
| 工作区初始化与默认 Profile 可解析 | `InitCommandTest`（3 例，含真实 `ProfileLoader` 回归） | 通过 |
| Profile 生命周期与脱敏 | `ProfileCommandTest`（3 例） | 通过 |
| 轻命令空结果与不泄漏凭证 | `QueryCommandTest`（2 例） | 通过 |
| 重命令运行时边界 | `RuntimeCommandTest`（2 例） | 通过 |
| CliChannel 交互协议 | `CliChannelTest`（2 例） | 通过 |
| 会话三元组幂等与隔离 | `SessionManagerTest`（3 例） | 通过 |
| 会话 JSON 往返与重启读回 | `JpaSessionStoreTest`（3 例） | 通过 |
| 控制台编码接管边界 | `ConsoleEncodingTest`（2 例） | 通过 |
| 端到端多轮对话 + 重启恢复 | 真实进程 + 真实 DeepSeek，手工验证 | 通过（用户确认） |

手工端到端脚本：`oryxos init` → 设 `DEEPSEEK_API_KEY` → `oryxos chat` → 问答两轮 → Ctrl+C → 重新 `oryxos chat` → 第三轮带上下文继续（会话按 `(cli, local, default)` 三元组从 SQLite 恢复）。

---

## 剩余未完成项

按建议优先级排列。

**P1 — 影响"收口完成"判定**

- `T058`：执行 `/speckit.analyze` 做跨 artifact 一致性检查（AGENTS.md 硬性要求，每个 user story 结束后跑一次）。

**P2 — 功能补全（面向可用性）**

- `T023`：`session list` 接真实 `SessionStore`，输出会话摘要与过滤。目前只有 `SessionStore.list()` 端口就绪。
- `T035`：补齐"Agent 失败 + 保存失败"组合下的错误可见性断言。
- `T026`：补 `JpaSessionStoreTest` 的 Profile 删除后历史保留、持久化失败可见两条断言。
- `T038`：新增 `ProfileSessionRetentionTest`，把"删 Profile 不删历史"固化为回归。
- `T028`：断言 `--profile` 值确实传入 `RuntimeLauncher`。

**P3 — 质量增强（可选）**

- `T054`：并发 `getOrCreate`、重复 `save` 的回归。
- `T043`/`T046`：完整装配断言与三入口共享 Session 的 smoke IT。
- `T052`：把已手工验证的端到端流程固化为无网络自动化测试。
- `T053`：模型/工具/持久化错误路径的脱敏专项。
- `T010`：既有库存在重复活动 Session 时的去重迁移脚本。

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 无依赖，可立即执行。
- **Phase 2 Foundational**: 依赖 Setup；阻塞所有用户故事，尤其是 Session 相关实现。
- **Phase 3 User Story 1**: 依赖 Foundational；是推荐 MVP，主要验证工作区和轻命令。
- **Phase 4 User Story 2**: 依赖 Foundational；复用 US1 初始化的工作区，但可用临时 Fixture 独立测试。
- **Phase 5 User Story 3**: 依赖 Foundational；可与 US1 并行开发，最终由 US2 使用 Profile 选择能力。
- **Phase 6 User Story 4**: 依赖 Foundational 和 RuntimeLauncher；建议在 US2 的重命令接线完成后实现。
- **Phase 7 Polish**: 依赖所有目标用户故事完成。
- **Phase 8 实现期新增**: 与 Phase 4/6 并行产生，已在实现中完成。

### User Story Dependencies

- **US1 (P1)**: 只依赖 Foundational；推荐作为 MVP 首先交付。
- **US2 (P1)**: 只依赖 Foundational；需要 AgentService、ProfileRegistry 和 SessionStore 测试替身，不依赖 US1 的具体命令实现。
- **US3 (P2)**: 只依赖 Foundational；可独立验证 Profile 文件生命周期，完成后为 US2 提供可选 Profile。
- **US4 (P2)**: 依赖 Foundational 和共享 RuntimeLauncher；与 US2 存在运行时接线关联，但不新增 Agent 逻辑。

### Within Each User Story

- 测试任务先写并确认失败，再实现对应功能。
- 数据模型/端口先于服务，服务先于命令接线，命令接线先于端到端验证。
- 同一文件的任务按编号顺序执行；标记 `[P]` 的任务只涉及不同文件且没有未完成依赖。

### Parallel Opportunities（剩余任务适用）

- `tasks.md` 剩余项分布在互不重叠的文件里，可并行：
  - `T023`（`oryxos-cli` 的 `SessionCommand`）
  - `T026`/`T038`（`oryxos-storage` 测试）
  - `T028`/`T052`/`T053`（`oryxos-cli` 测试）
  - `T035`（`oryxos-storage` + `oryxos-channel-cli` 错误路径）
  - `T054`（`oryxos-core` + `oryxos-storage` 并发回归）
- 串行约束：`T058`（`/speckit.analyze`）应在其余项收敛后执行，避免在同一轮里反复改同一批文档。

---

## Implementation Strategy

### 实际执行结果

1. Setup + Foundational：Session 端口、SQLite 适配、工作区解析全部落地。
2. US1：工作区初始化与 9 个轻命令可用；`init` 生成的默认 Profile 可被真实 `ProfileLoader` 解析。
3. US2：`CliChannel` 委托 `AgentService`，会话按三元组幂等、跨重启恢复。
4. US3：Profile 生命周期可用，详情脱敏、默认 Profile 受保护。
5. US4：`chat`/`serve`/`gateway` 共享同一 Spring 运行时；`serve` 复用既有 REST 控制器，`gateway` 为常驻边界。
6. Phase 8：实现期补齐启动入口分流、Profile 模板、Windows 编码、命令包装脚本四项原计划外的工作。
7. Polish：全量测试通过；`quickstart`、`contracts`、`docs/` 与实现同步。

### Suggested Story Commits

- `feat(cli): initialize workspace and light commands`
- `feat(cli): wire chat channel and persistent sessions`
- `feat(cli): manage profiles`
- `feat(cli): wire serve and gateway modes`
- `feat(cli): add runtime launcher and command split`
- `fix(cli): align default profile template and console encoding`
- `test(cli): add end-to-end verification`

## Notes

- 每个任务都包含具体文件路径；实际实现中对部分测试文件名做了等价调整（如 `RuntimeCommandTest` 合并 serve/gateway 边界用例），已在对应任务下标注。
- 不新增 `oryxos-tool`、`oryxos-memory` 或其他拆分模块；Tool/Memory/ReAct 继续复用既有能力。
- 唯一新增依赖是 `oryxos-cli` 与 `oryxos-channel-cli` 的 `spring-boot-starter-test`（test scope），属既有技术栈，未引入新的第三方库。
- Maven 模块数量仍为 9 个；`bin/oryxos.cmd` 是运行包装脚本，不是模块。
- 真实模型调用不作为默认 Maven 测试前置条件；真实调用 smoke test 显式跳过或要求环境变量。
- 不执行 Git commit，除非用户明确要求。
