---

description: "Notify 出站通知模块实施任务"
---

# Tasks: Notify 出站通知模块

**Input**: Design documents from `/specs/004-notify/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/notify.md`、`quickstart.md`

**Tests**: 本特性明确要求测试（`contracts/notify.md` 第 6 节列出 12 条 Required Verification），测试任务包含在各用户故事中。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。

**Status legend**: `[X]` 已完成并验证；`[ ]` 未完成（附原因）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: 所属用户故事（US1 ~ US4）

## Path Conventions

单体 Maven 多模块仓库，路径均为仓库根相对路径。本特性主体落在 `oryxos-tool`，另有两处跨模块收口改动。

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: `oryxos-tool` 模块此前没有 `src/test` 目录、没有任何测试依赖，必须先补齐。

- [X] T001 为 `oryxos-tool/pom.xml` 增加 test scope 的 `spring-boot-starter-test`（JUnit 5 + Mockito + AssertJ），并创建 `oryxos-tool/src/test/java/io/oryxos/tool/` 目录树
- [X] T002 [P] 在 `oryxos-tool/src/test/java/io/oryxos/tool/support/FakeWebhookServer.java` 实现本地假 webhook 脚手架：基于 JDK `com.sun.net.httpserver.HttpServer`，随机端口启动，记录（方法、路径、请求体、Content-Type），可配置返回状态码，支持优雅关闭

**Checkpoint**: `mvn -pl oryxos-tool -am test` 可执行。

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 渠道抽象三件套。

- [X] T003 [P] 在 `oryxos-tool/src/main/java/io/oryxos/tool/notify/NotifyTarget.java` 新增 `record NotifyTarget(String channelType, Map<String, String> config)`，构造时把 `config` 收敛为不可变映射
- [X] T004 [P] 在 `oryxos-tool/src/main/java/io/oryxos/tool/notify/NotifyChannelAdapter.java` 新增接口 `void send(NotifyTarget target, String content)`，签名中不出现任何渠道/协议特有词汇
- [X] T005 在 `oryxos-tool/src/main/java/io/oryxos/tool/notify/NotifyConfigException.java` 新增运行时异常，承载渠道配置类失败
      **实际多出 2 个类**（见「实现期补充」）：`NotifySendException`（携带可重试标记）与 `NotifyUrls`（脱敏与 host 提取的唯一实现）。

**Checkpoint**: 抽象就位，可被实现与解析器复用。

---

## Phase 3: User Story 1 - 把内容推送到配置好的 webhook (Priority: P1)

**Goal**: 配好 webhook 地址后，能把一段文本 POST 过去。

**Independent Test**: 本地假 webhook 断言收到 POST 与 `content`；换 URL 确认地址来自配置；500 时异常上抛。

- [X] T006 [P] [US1] `WebhookNotifyAdapterTest`：覆盖 POST + 请求体 + Content-Type、地址来自配置、中文 UTF-8 传输、5xx 可重试、4xx 不可重试、缺 url 不发请求、网络不可达、错误信息不含完整地址（共 7 例）
- [X] T007 [US1] `WebhookNotifyAdapter` 实现：JDK `HttpClient`（连接 10s / 请求 30s，与既有 `HttpTool` 一致），POST `application/json`，体 `{"content": ...}`；非 2xx 按 5xx/4xx 区分可重试性

**Checkpoint**: US1 独立可验——不依赖 Agent、Profile、Sandbox。

---

## Phase 4: User Story 2 - Agent 在对话中主动推送 (Priority: P1)

**Goal**: Agent 调用 `notify`，从当前 Profile 解析渠道并推送。

- [X] T008 [P] [US2] `NotifyTargetResolverTest`：缺省取第一个、按 type 忽略大小写匹配、配置值收敛为 String 且不可变、无处理上下文、未配置渠道、空列表、渠道不存在（列出可用渠道）、缺 type、类型不支持、缺 url、config 为空、url 非法、非 http 协议（共 13 例）
- [X] T009 [P] [US2] `NotifyToolTest`：工具名/描述/入参 schema 符合契约、未配置渠道失败、内容为空拒绝、成功推送传递正确目标与内容、显式渠道不存在、发送失败按可重试标记返回（共 10 例，含 US3 的顺序用例）
- [X] T010 [P] [US2] `NotifyAutoConfigurationTest`：装配后 `ToolRegistry` 中存在 `notify`
- [X] T011 [US2] `NotifyTargetResolver` 实现：按 `data-model.md` 第 3 节的规则与顺序抛 `NotifyConfigException`；支持类型清单为 `webhook`
- [X] T012 [US2] `NotifyTool` 实现 `OryxTool`：从 `AgentContext.current()` 取 Profile → 解析 → 校验 → 发送；配置类失败 `retryable=false`，网络类 `true`
- [X] T013 [US2] `NotifyAutoConfiguration` 装配：声明 adapter/resolver/tool Bean 并注册进 `ToolRegistry`；**只注册 `notify`**
      **实现细节**：用一个专门的登记器 Bean（`InitializingBean`）做注册，而**不是**再声明一个 `ToolRegistry` 类型的 Bean——后者会与 `CoreAutoConfiguration` 既有的 `ToolRegistry` Bean 冲突，使按类型注入处产生歧义。

**Checkpoint**: `notify` 可被 Agent 调用；未配置渠道时明确失败而非假成功。

---

## Phase 5: User Story 3 - 出站请求先过白名单再发送 (Priority: P2)

**Goal**: 校验收敛为"永远早于发送"，校验失败时一次外发都不发生。

- [X] T014 [US3] 在 `NotifyToolTest` 增加：`InOrder` 断言 `checkHttpUrl` 先于 `send`；校验抛 `SandboxViolationException` 时 `verify(adapter, never()).send(...)`；未装配校验器时放行且仍然发送
      **部分达成**：③"未装配时**日志**出现告警"未做成自动化断言——`oryxos-tool` 的测试类路径上没有日志实现（只有 `slf4j-api`，无 logback），断言日志需要额外引入日志后端。当前以"放行且仍然发送"覆盖可观测行为，WARN 本身由代码保证 + 人工确认。已在 `contracts/notify.md` 标注该限制。
- [X] T015 [US3] `NotifyTool` 接入可选校验：构造参数接收可为 `null` 的 `SandboxChecker`；已装配则调用 `checkHttpUrl(url)`，未装配则输出一次 WARN；调用点严格位于 `adapter.send` 之前

**Checkpoint**: 顺序不变式被测试钉死。

---

## Phase 6: User Story 4 - 通知地址不得泄漏 (Priority: P3)

- [X] T016 [P] [US4] `ProfileCommandTest` 增加 2 例：带 `key=SECRET123` 的 webhook 地址不出现在 `profile show` 输出；默认 Profile 模板中的示例占位地址同样不外泄
- [X] T017 [P] [US4] `WebhookNotifyAdapterTest` / `NotifyToolTest` 补断言：失败信息与返回结果中不出现完整 URL（只允许 host）
- [X] T018 [US4] `ProfileCommand` 敏感关键字集合补 `url`
      **代价（已记录）**：provider 的 `base-url` 也会被遮挡。接受——它对排查无足轻重，而漏掉一个 webhook 地址等于交出群组推送权限。

**Checkpoint**: 地址与凭证在展示层、错误信息层均不可见。

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T019 [P] `ToolApiController` 硬编码清单补 `notify`，保持接口与注册表一致
- [X] T020 [P] `ProfileTemplates` 默认 Profile 模板补 `notify_channels` 注释示例（仍可被 `ProfileLoader` 正常解析，`InitCommandTest` 回归通过）
- [X] T021 使用 JDK 21 与固定 Maven 3.9.9 执行仓库根 `mvn test`，10 个模块全绿
- [X] T022 按 `quickstart.md` 第 5 节完成人工验收：真实 webhook 收到消息、未配置渠道时 Agent 明确报失败、Profile 详情看不到地址
      **由使用方在真实环境执行并确认通过**。其中第 1 项（真实外部推送）只能人工确认；第 2、3 项另有自动化覆盖（`NotifyToolTest`、`ProfileCommandTest`），人工确认属二次核对。

---

## 实现期补充

计划外新增的两个类，理由如下：

| 类 | 为什么需要 |
|---|---|
| `NotifySendException` | `contracts/notify.md` 区分了 5xx/网络（可重试）与 4xx（不可重试），需要一个能携带 `retryable` 的发送失败异常；用 `NotifyConfigException` 承载会混淆"配置问题"与"网络问题" |
| `NotifyUrls` | 脱敏逻辑（掩码 + host 提取）要同时被适配器、解析器、工具使用，必须只有一份实现，否则总有一处会漏掉把完整地址打出去 |

## 验证记录

| 验证项 | 方式 | 结果 |
|---|---|---|
| 工具模块测试 | `mvn -pl oryxos-tool -am test` | 32 例全绿（首个实现轮次即通过） |
| 全量回归 | 仓库根 `mvn test` | 10 个模块 BUILD SUCCESS |
| 全上下文装配 | 实机启动 `chat --profile default` | `Found 4 JPA repository interfaces` → `已注册内置工具: notify（当前注册表共 1 个）` → `Started OryxosLauncher`，无 Bean 冲突、无 `APPLICATION FAILED` |
| 真实外部推送 | 真实 webhook（使用方环境） | ✅ 已通过（T022） |

> 说明：全应用 Spring 上下文平时只被 `*IT` 加载，而 `mvn test` 不执行 IT。因此本次额外做了**实机启动**验证，专门确认新增装配配置不会与既有 `ToolRegistry` Bean 冲突——这是单测覆盖不到的盲区。

## 收尾记录（2026-10-10）

人工验收通过后的收口轮次：**不改动任何行为，只对齐产出状态、文档与注释**。

| 项 | 动作 |
|---|---|
| 产出状态 | `spec.md`、`contracts/notify.md` 由 Draft/Planned 改为 Implemented；本文件 T022 打勾 |
| 教学文档 | `docs/class/第19节` 补「实现说明」，逐条列出示意代码与仓库实现的 6 处差异；修正 `notify_channels` 的 YAML 结构（`url` 在嵌套 `config` 下） |
| 技术方案 | `docs/TechnicalSolution.md` §6.2 补 `notify`，并标注"当前仅 `notify` 实际注册进 `ToolRegistry`" |
| 代码注释 | `WebhookNotifyAdapter` 补充装配方式差异说明；修正"告警一次"的措辞（实为抑制刷屏，并发下非严格单次） |

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 Setup → 阻塞全部（无测试基建就无法写测试）
- Phase 2 Foundational → 阻塞全部用户故事
- Phase 3 US1 → 依赖 Foundational，可独立验证
- Phase 4 US2 → 依赖 US1（需要适配器才能端到端发送）
- Phase 5 US3 → 依赖 US2（在 `NotifyTool` 上加校验点）
- Phase 6 US4 → 与 US1~US3 弱耦合
- Phase 7 Polish → 依赖全部故事

### Parallel Opportunities

- T003 / T004 可并行
- T006 / T008 / T009 / T010 可并行（不同文件）
- T016 / T017 可并行
- T019 / T020 可并行（不同模块）
- 注意：`NotifyToolTest.java` 被 T009、T014、T017 先后修改，**这三条不得并行**

## Implementation Strategy

### MVP First

1. Setup → 2. Foundational → 3. US1 → **停下来独立验证**（假 webhook 收到消息）→ 4. US2 形成可演示能力

## Notes

- **不新增 Maven 模块、不新增第三方运行期依赖**；测试侧也不引入 MockWebServer/WireMock，用 JDK `HttpServer` 起本地假 webhook
- 不使用 Spring AI 的 `@Tool` 注解与自动执行；统一 `OryxTool` + 既有 `ToolExecutor` 调度（Constitution III）
- 渠道解析器放 `oryxos-tool` 而非 `AgentContext`，避免 core 反向依赖工具模块类型
- 白名单校验器为**可选依赖**，未装配时放行并告警——"占位"的已知代价已在 `spec.md` A-003 与 `plan.md` D-04 显式记录
- 未一并接入 `FileTool`/`ShellTool`/`HttpTool`：那是既有独立缺口，混进来会扩大回归面
- 不执行 Git commit，除非用户明确要求
