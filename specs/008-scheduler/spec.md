# Feature Specification: 定时任务模块（AgentScheduler）

**Feature Branch**: `008-scheduler`

**Created**: 2026-10-20

**Status**: Draft

**Input**: User description: "docs/class/第25节：定时任务模块 原理解析、实现与代码讲解.md —— 补上第三种触发源：到点自己拼一条消息，交给与 CLI / Web 完全相同的 AgentService 入口"

**Source document**: `docs/class/第25节：定时任务模块 原理解析、实现与代码讲解.md`

## 背景与定位

CLI 与 Web Service 都是"人推"：有外部实体发一条消息进来，Agent 才动。定时任务是**第三种触发源**，
"钟推"——到点由系统自己拼一条消息，喂给**同一个** `AgentService`。ReActLoop、ToolExecutor、
ProviderService 一个字都不用改，这既是本节的功能目标，也是对本节范围的硬约束。

**边界声明（这一节最容易被做胖）**：

- 本模块**只做**三件事：读 Profile 的 `schedules` 配置、注册到 Spring `TaskScheduler`、到点调用 `AgentService`。
- **不做**任务编排/工作流（步骤、依赖、重试策略、条件分支）、不做分布式协调（选主、分布式锁、租约）、
  不做任务管理 API（增删改查、立即执行按钮）、不做失败重试与告警。
- 消息内容归 Profile / SKILL.md，处理过程归 ReActLoop——两者都不在调度层。
- 不新增 Maven 模块（核心阶段固定 9 个模块）：调度层落在 `oryxos-core` 内，属"引擎的第三种入口"。

## 与既有实现的冲突及收敛结论

仓库里 `Profile.schedules` **字段早已存在，但只有 `cron` + `task` 两个字段，且没有任何消费方**（死字段）。
与 25 节设计存在以下冲突，逐条给出结论，作为本轮实现契约：

| # | 冲突点 | 文档要求 | 仓库现状 | 本轮结论 |
|---|---|---|---|---|
| **C-1** | 定时配置的字段形状 | `ScheduleConfig(id, cron, zone, message)` | `ScheduleConfig(cron, task)`，无 id、无时区、无 message | **扩展为 `id / cron / timezone / message`，`task` 保留为 `message` 的兼容别名**（`message` 优先，`task` 兜底）。理由：`schedules` 目前没有任何 YAML 使用，扩展是净增能力；保留 `task` 可避免破坏未来可能已存在的旧写法 |
| **C-2** | 任务 id 是否必填 | 文档直接用 `sc.getId()` 作锁键，默认其存在 | 无 id 字段 | **`id` 必填**：它是日志定位与防重叠锁的键。缺失即配置缺陷 → 该条跳过 + ERROR（可定位到 profile 与条目序号）。不做"自动生成 id"，因为自动值会随条目顺序变化，锁与日志都不稳定 |
| **C-3** | 锁的键空间 | `taskLocks.computeIfAbsent(sc.getId(), ...)` | — | **键 = `profileName#id`**。id 是 Profile 内局部标识，只用 id 会让两个 Profile 的同名任务互相阻塞（串行化错误）。这是对文档的一处修正，行为上更严格而非更松 |
| **C-4** | 模块归属 | 未指明（`@Component` 暗示 Spring 扫描） | 9 模块固定；core 只用 spring-context，不用 `@Component` 扫描 | 放 `oryxos-core` 的 `io.oryxos.core.schedule`，由 `CoreAutoConfiguration` 显式装配（仓库惯例：显式 Bean，不靠注解扫描） |
| **C-5** | 启动注册时机 | `@PostConstruct` | core 无 `jakarta.annotation` 依赖；且注册必须发生在 Profile 加载之后 | 实现 `InitializingBean`（注册）+ `DisposableBean`（取消任务）。Profile 由 `ProfileLoader` 在 Bean 创建期同步加载，`ProfileRegistry` 已是就绪状态，顺序天然满足 |
| **C-6** | 配置错误如何处理 | 未定义（只讲了运行时失败隔离） | 005/007 的惯例：配置错误启动即报 | **单条非法（cron / 时区 / 缺 id / 缺消息）→ ERROR + 跳过该条，其余照常注册**；不因一条坏配置拖垮整个应用。日志必须含 profile、条目序号、原始值 |
| **C-7** | 审计 | "`llm_calls` / `tool_invocations` 该怎么写还怎么写" | `AgentService` 已有 `agent_runs` + 下游明细审计 | **不新增任何审计代码**：定时触发走 `AgentService.process(session, message)`，既有链路自动覆盖。本特性只保证"不绕过入口" |
| **C-8** | 调度器实例 | `ThreadPoolTaskScheduler` 直接注入 | core 无 Boot 依赖，但 `ThreadPoolTaskScheduler` 属 spring-context，可用 | 由 `CoreAutoConfiguration` 产出 `ThreadPoolTaskScheduler` Bean（线程名前缀 `oryxos-scheduler-`、池大小 `oryxos.scheduler.pool-size`、默认 2），`AgentScheduler` 依赖 `TaskScheduler` 抽象以便测试替身 |

## 现状与缺口

| 缺口 | 当前表现 |
|---|---|
| 配置字段是死的 | `Profile.schedules` 能解析但无人读取，配了也没有任何效果 |
| 没有第三种触发源 | 只有 CLI / Web 能驱动 `AgentService`，Agent 无法"到点自己动" |
| 无防重叠手段 | 没人保证同一个定时任务不会叠加执行 |
| 定时触发的失败无处体现 | 没有调度层，自然也没有"失败只记日志、不崩调度器"的隔离 |
| 时区无表达 | 配置里没有时区字段，只能吃服务器默认时区 |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 到点自动跑，且走与人工完全相同的入口 (Priority: P1)

在 Profile 里写一条 `schedules`（id / cron / timezone / message），重启后到点 Agent 自动开始一轮处理：
`AgentService` 收到这条 message，跑 ReAct 循环，产出记入既有审计。调度层不参与"怎么想、怎么调工具"。

**Why this priority**: 这是本特性的全部价值。没有它，日报/巡检这类 Agent 永远只能靠人手动触发。

**Independent Test**: 不依赖模型、不真等时间。注册阶段用 mock `TaskScheduler` 抓注册参数
（cron + 时区）；触发阶段直接调 `runOnce`，断言 `AgentService` 收到正确消息与会话。

**Acceptance Scenarios**:

1. **Given** 一个配置了一条 `schedules` 的 Profile，**When** 应用启动完成，**Then** 调度器注册了 **1** 条任务，日志含 profile、id、cron、时区。
2. **Given** 注册的任务被触发，**When** `runOnce` 执行，**Then** `AgentService.process(session, message)` 被调用恰好一次，message 与配置一致。
3. **Given** 同一 Profile 两次不同时刻触发，**When** 分别执行，**Then** 两次拿到**同一个 Session**（会话身份固定 `("scheduler","scheduler",profileName)`）。
4. **Given** 一个没有任何 `schedules` 的 Profile，**When** 启动，**Then** 注册 0 条，不影响其它 Profile 与本进程其它能力。
5. **Given** 配置只写了 `task` 没写 `message`，**When** 触发，**Then** 用 `task` 作为消息内容（兼容旧写法）。

---

### User Story 2 - 上一次没跑完，本次触发直接跳过 (Priority: P1)

一次 ReAct 处理可能比调度间隔更长。此时同一任务的并发触发必须被**跳过**（不排队、不堆积），
不同任务之间不得互相阻塞。

**Why this priority**: 不防重叠，日报 Agent 会在慢模型下叠加执行，产生重复推送与重复副作用。

**Independent Test**: 手动占住锁后调 `runOnce`，断言 `AgentService` 零调用；占住 A 任务的锁时 B 任务仍可执行。

**Acceptance Scenarios**:

1. **Given** 某任务的锁已被占用（上一次还在跑），**When** 本次触发到来，**Then** 直接跳过并记 INFO，`AgentService` 调用次数为 **0**。
2. **Given** Profile A 的 `daily` 任务正在执行，**When** Profile B 的同名 `daily` 任务触发，**Then** **照常执行**（锁键含 profileName，不互相阻塞）。
3. **Given** 上一次执行已结束，**When** 下一次触发到来，**Then** 正常执行（锁已释放，不会被永久卡住）。

---

### User Story 3 - 一次失败不崩调度器，且留痕可查 (Priority: P2)

某次定时任务抛异常（模型报错、超时、工具失败）时：异常不得外抛到调度线程、锁必须释放、
下一次触发仍能正常进入，审计照既有链路落库。

**Why this priority**: 调度器被一次异常搞挂或任务被永久卡死，都是"静默停摆"——比报错更难发现。

**Independent Test**: 让 `AgentService` 抛异常，断言 `runOnce` 不外抛；再触发一次，断言仍能进入（证明锁真的释放）。

**Acceptance Scenarios**:

1. **Given** `AgentService.process` 抛 `RuntimeException`，**When** `runOnce` 执行，**Then** 异常不外抛，ERROR 日志含任务键与原因。
2. **Given** 上一次执行抛了异常，**When** 下一次触发到来，**Then** 正常执行（锁在 `finally` 释放）。
3. **Given** 定时触发执行了一次处理，**When** 查看审计，**Then** 记录与人工触发形态一致，不出现"定时专用"的新表或新字段。

---

### User Story 4 - 配置驱动、时区显式，非法配置不拖垮启动 (Priority: P2)

改触发时间只需要改 YAML、重启，不需要改代码重新编译。cron 必须能带上时区；
一条坏配置（非法 cron、非法时区、缺 id/消息）只让**这一条**不生效，并在日志中可定位。

**Why this priority**: "配置即 Agent"是项目一贯原则；坏配置静默失效或拖垮启动，都会让运维失去信任。

**Independent Test**: 逐条构造非法配置，断言启动成功、合法条目照常注册、日志可定位（含 profile 与原始值）。

**Acceptance Scenarios**:

1. **Given** 配置 `cron: "0 9 * * *"` 与 `timezone: Asia/Shanghai`，**When** 注册，**Then** 触发器的下一次执行落在**东八区 9 点**（而非服务器默认时区）。
2. **Given** 未配置 `timezone`，**When** 注册，**Then** 使用系统时区，并在启动日志中**显式写明**所用时区（不让约定俗成替用户做主）。
3. **Given** 一条非法 cron + 一条合法配置，**When** 启动，**Then** 应用正常启动，仅合法条被注册，非法条 ERROR 日志含 profile、条目序号、原始 cron。
4. **Given** 非法时区名 / 缺 `id` / 消息为空，**When** 启动，**Then** 同上的"跳过 + 可定位"，且不影响其它条目。

---

### Edge Cases

- **Profile 的 `schedules` 为 null / 空列表**：视为没有定时任务，不注册、不告警噪声。
- **同一 Profile 内 `id` 重复**：后一条跳过并 ERROR（锁与注册键相同，不允许模糊语义）。
- **调度器已关闭时注册**：`schedule(...)` 返回 null，视为注册失败，ERROR 并继续处理其它条目。
- **应用关闭**：已注册任务全部取消，调度线程池随 Bean 销毁关闭，不留悬挂线程。
- **消息内容为空白**：跳过并 ERROR（发给 Agent 的空消息只会换来一次无意义的模型调用）。
- **极长任务**：不属于本特性的处理范围（处理超时由 `AgentService` / ReActLoop 既有机制兜底）。

## Requirements *(mandatory)*

### Functional Requirements

**注册与触发**

- **FR-001**: 启动时系统 MUST 扫描全部 Profile 的 `schedules`，为每条合法配置注册一个 Spring `TaskScheduler` 任务。
- **FR-002**: 注册 MUST 使用 `CronTrigger(cron, zoneId)` 动态注册，MUST NOT 使用 `@Scheduled` 注解（cron 写死在代码里违反"配置即 Agent"）。
- **FR-003**: 到点触发 MUST 调用与 CLI / Web 完全相同的入口 `AgentService.process(session, message)`，MUST NOT 自建处理链路。
- **FR-004**: 会话身份 MUST 固定为 `("scheduler", "scheduler", profileName)`；同一 Profile 的历次触发 MUST 复用同一 Session。
- **FR-005**: 消息内容 MUST 取 `message`；未配置时回退 `task`（兼容旧字段）。

**并发与隔离**

- **FR-006**: 每个任务 MUST 有一把进程内锁；拿不到锁时 MUST 跳过本次触发，MUST NOT 排队或等待。
- **FR-007**: 锁键 MUST 包含 profileName 与任务 id，使不同 Profile 的同名任务互不阻塞。
- **FR-008**: 锁 MUST 在 `finally` 中释放，成功或失败都不得遗留。
- **FR-009**: 单次执行的任何异常 MUST NOT 外抛到调度线程；MUST 记 ERROR 日志（含任务键与原因）。
- **FR-010**: 单条配置非法 MUST NOT 阻断启动或影响其它条目；MUST 记 ERROR（含 profile、条目序号、原始值）。

**配置与时区**

- **FR-011**: `ScheduleConfig` MUST 支持 `id` / `cron` / `timezone` / `message` 四个字段，并保留 `task` 作为 `message` 的兼容别名。
- **FR-012**: `id` 必填；Profile 内重复的 `id` MUST 只让第一条生效。
- **FR-013**: 配置了 `timezone` 时 MUST 按该时区计算 cron；非法时区 MUST 跳过该条并 ERROR。
- **FR-014**: 未配置 `timezone` 时 MUST 使用系统时区，并在启动日志中写明实际使用的时区。
- **FR-015**: 调度线程池池大小 MUST 可配置（`oryxos.scheduler.pool-size`），默认 2。

**审计与生命周期**

- **FR-016**: 定时触发 MUST 复用既有审计链路（`agent_runs` 及其下游 `llm_calls` / `tool_invocations`），MUST NOT 新增定时专用审计结构。
- **FR-017**: 应用关闭时 MUST 取消所有已注册任务，MUST NOT 留下悬挂线程。

### Key Entities

- **定时配置（ScheduleConfig）**：Profile 内的一条定时声明。属性：`id`（Profile 内唯一）、`cron`（Spring 6 段表达式）、`timezone`（可空，默认系统时区）、`message`（到点要说的话）、`task`（兼容别名）。
- **调度器（AgentScheduler）**：薄调度层。职责：注册、到点取锁、拼消息、调 `AgentService`、放锁。
- **任务键（taskKey）**：`profileName#id`，同时用于防重叠锁、注册去重与日志定位。
- **调度线程池（TaskScheduler）**：Spring 提供，线程名前缀 `oryxos-scheduler-`。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 合法 `schedules` 的注册成功率 **100%**（每条合法配置都有且只有一个已注册任务）。
- **SC-002**: 重叠触发被跳过的比例 **100%**，且跳过时 `AgentService` 调用次数为 **0**。
- **SC-003**: 单次失败后下一次触发可正常进入的比例 **100%**（无永久卡死）。
- **SC-004**: 非法配置导致的启动失败次数为 **0**，且每条非法配置在日志中可定位（profile + 序号 + 原始值）。
- **SC-005**: cron 时区语义正确：同一表达式在东八区与 UTC 下的触发时刻相差 8 小时（用固定时钟断言）。
- **SC-006**: 定时触发不引入新的审计结构（新增表/字段数 = **0**）。
- **SC-007**: 全仓 `mvn test` **BUILD SUCCESS**，测试失败数 **0**。

## Assumptions

- **A-001（现状：`schedules` 是死字段）**：`Profile.schedules` 已存在但无消费方，且默认 Profile 未配置。因此本特性扩展字段属净增能力，不构成行为回退。
- **A-002（决策：`id` 必填，不做自动生成）**：见 C-2。代价：配置多写一个字段；收益：锁键、注册键、日志定位三者一致且稳定。
- **A-003（决策：锁键含 profileName）**：见 C-3。代价：与文档代码略有差异；收益：不同 Profile 的同名任务不会互相阻塞。
- **A-004（决策：单条非法配置只跳过该条）**：见 C-6。代价：错误不会让启动失败，必须靠日志发现；因此日志必须可定位（profile + 序号 + 原始值）。
- **A-005（决策：`task` 保留为兼容别名）**：见 C-1。代价：两个字段表达同一件事，需在文档写明优先级（`message` 优先）。
- **A-006（决策：不加 `enabled` 总开关）**：文档未要求；启用/停用某条定时任务可通过删除配置项完成。若后续需要"临时停用"，再作为独立变更评估。
- **A-007（范围：不做分布式协调）**：核心阶段单实例，进程内锁足够。多实例归属、租约、选主属扩展阶段。
- **A-008（范围：不做重试与告警）**：失败只保证"不崩 + 留痕"，重试与失败告警不在本轮。
- **A-009（不做任务管理 API）**：无增删改查、无"立即执行"接口；触发方式只有 cron。
