# Feature Specification: 插件化 Agent —— 一个目录定义一个会自己跑的 Agent

**Feature Branch**: `class-29`

**Created**: 2026-10-28

**Status**: Implemented（2026-10-30 真模型验收通过）

**Input**: User description: "docs/class/第29节：插件化 Agent 一个目录定义一个会自己跑的 Agent.md —— 把『定义一个 Agent』从写一份 Profile YAML 变成丢一个目录，由一个目录派生出会自己跑的 Agent"

**Source documents**:

- `docs/class/第29节：插件化 Agent 一个目录定义一个会自己跑的 Agent.md`

---

## 背景与定位

到第 28 节为止，底座（Provider / ReAct / Tool / Memory / Sandbox / Scheduler / Web）齐了，但"定义一个 Agent"仍然是把配置硬写进 `profiles/*.yaml`。本节补的是**"定义一个 Agent 并让它自己跑"的标准机制**：往 `.oryxos/agents/` 丢一个目录，解析其 `AGENT.md` 的 frontmatter 派生出 `Profile`，走既有注册与校验，到点由 `AgentScheduler` 自动触发——**全程不写 Java、不改底座控制流**。

机制本体是三步：**解析目录 → 派生 Profile → 注册进 Registry/Scheduler**。运行时与 CLI/Web 人推完全同一条 `AgentService.process(session, message)`。

**边界声明（范围）**：

- **不动 Web 层与 CLI 代码**：`GET /api/v1/profiles` 的响应结构、`profile list` 的展示、`/api/v1/profiles/skipped` 端点全部顺延到 30 节。本节只在 **core 侧**提供"被跳过 Agent"的数据。
- **做**运行时登记与注销的**内核能力**（`register` / `unregister`，见 US6），但**不对外暴露接口**；`reload` / 文件监听热加载 / `POST /api/v1/agents` 留 30 节。
- 不做 Agent 版本管理、Agent 市场/共享、跨 Agent 能力复用、容器级/网络级隔离。
- **不改 `ReActLoop` / `ToolExecutor` / `AgentService` / Provider / `ToolResult` 语义**。

---

## 与课件的偏差及收敛结论

课件是输入，但其中若干描述与当前代码不一致或会破坏既有行为。逐条结论如下（**这些是本 spec 的强制口径**）：

| # | 课件说法 | 代码现状 | 收敛结论 |
|---|---|---|---|
| **C-1** | frontmatter 里 `identity` 是 `{agent_name, prompt}`、`schedules` 用 `zone`、`notify_channels` 直接挂 `url` | `identity` 是 `String`；`schedules` 用 `timezone`；notify 是 `{type, config:{url}}`；Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`，写错**静默按默认跑** | **以代码 `Profile` 口径为准（单一 schema）**；仅 `identity` 额外接受对象形态（取 `prompt` 为主体、`agent_name` 作首行）。课件示例的 `AGENT.md` 按此重写 |
| **C-2** | 目录里的 `skills/*.md` 是"用到才读" | `Profile.skills` 是**每轮全量注入 system prompt** | 目录 `skills/` **不映射** `Profile.skills`，只作普通资源；frontmatter 里出现 `skills:` 视为**硬错**（跳过该 Agent 并点名），防止"写了却没生效" |
| **C-3** | "`ProfileRegistry` 现在不可变，本节改成可变 Map" | 本就是 `ConcurrentHashMap`，已有 `registerProfile/unregisterProfile/clear`；但**注销只删内存条目，不会取消该 Profile 的定时任务** | 做**命名对齐**（`register/remove/exists`）+ **补齐注销副作用**：注销一个 Agent 必须同时取消它的全部定时任务并清理句柄表（否则"删了还在跑"） |
| **C-4** | "一行底座都不动" | 实际要改 `Profile`（加 `agentDir`）、`ContextLoader`、`ProfileRegistry`、`AgentScheduler`、`ShellTool`、`FileTool`、`WhitelistSandbox`、`DefaultMemoryService` | 准确表述：**不改控制流，只加字段/加方法/换数据来源** |
| **C-5** | `shell` 只允许"解释器 + 本 Agent 的 scripts/" | 沙箱是全局单例、按**首 token** 匹配 `allowed-commands`（当前含 `echo`），没有任何目录概念 | **按 Profile 是否带 `agentDir` 分流**：带 `agentDir` → 新规则（解释器白名单 + 本目录 `scripts/` 真实路径）；不带（legacy `profiles/`）→ 沿用旧规则，**存量行为不回归**。解释器清单走配置项 |
| **C-6** | 资源加载"天然被限制在它自己的目录里" | `FileTool` 白名单是 `${user.dir}`，相对路径按 JVM cwd 解析 | 带 `agentDir` 的 Agent：**文件白名单收窄为它自己的目录**，相对路径按 `agentDir` 解析；legacy 保持原行为 |
| **C-7** | Agent 自带记忆（`save_memory` 记一笔） | 全局单文件 `<workspace>/memory/MEMORY.md`，所有 Agent 共享 | **每个带 `agentDir` 的 Agent 用 `<agentDir>/memory/MEMORY.md`**；legacy 回退全局文件。`MemoryService` 接口**签名不变**，由实现按 `AgentContext` 解析路径 |
| **C-8** | 脚本非 0 退出即失败 | `ShellTool` 从不看 `exitValue()`，一律 `ToolResult.ok(stdout)` | 改为**非 0 → `ToolResult.fail(不可重试)`**，并带上 stdout/stderr 尾部 |
| **C-9** | （未提） | `ToolResult.retryable` **没有任何消费者**（ReAct 不据此禁止重试），失败结果照旧回填给模型 | 保留为**纯标记**（用户明确只要标记）；不改 ReAct 行为 |
| **C-10** | （未提） | shell 输出**全量**拼接进上下文，无上限 | 新增 **32KB 截断**，截断时显式标注 |
| **C-11** | 验收要求"跳过项在页面上看得见" | 本节不动 Web/CLI | 只在 core 侧提供 `List<SkippedAgent>`，Web 透出顺延 30 节；验收项相应改写 |
| **C-12** | （未提） | `ContextLoader` 默认读工作区根 `AGENTS.md/SOUL.md/USER.md` | 目录 Agent **不读**默认三件套（避免所有 Agent 串上同一份人设）；只有显式 `bootstrap.files` 才读，且相对 `agentDir` 解析 |
| **C-13** | "目录名 = 唯一标识" | `ProfileLoader` 用**文件名**当 name | 目录名当 name；**frontmatter 里的 `name` 被忽略**，不一致时 WARN 点名 |
| **C-14** | 主文件缺必填项（名字/运行配置）→ 报错点名 | `ProfileLoader.validate` 对**缺少 provider 段只 WARN、仍注册**；provider 名非法才抛异常；`ToolResolver` 对未注册 tool **只告警并排除** | **名字**不存在"缺失"（取目录名）；**缺 `provider` 段收紧为硬错**（两条来源同步，属对 legacy 的行为变更）；**未注册 tool 保持告警 + 排除**，不升级为硬错 |

---

## 现状与缺口

| 缺口 | 当前表现 |
|---|---|
| 无目录概念 | 只有 `profiles/*.yaml`；没有"一个 Agent 一个目录"的解析器 |
| `Profile` 不知道自己在哪 | 无 `agentDir`，`ContextLoader` 无法定位 Agent 自己的正文与资源 |
| 正文无处可放 | 只能靠 `identity` 字符串 + 工作区级 Bootstrap 文件 |
| 脚本无边界 | `shell` 可以跑任一白名单命令；`python 任意路径.py` 无法拦截 |
| 记忆全局共享 | 所有 Agent 读写同一份 `MEMORY.md`，会互相串味 |
| 加载失败不可见 | `ProfileLoader` 只记日志；没有"哪些目录没加载成功"的清单 |
| 注册时序风险 | `AgentScheduler.afterPropertiesSet` 会全量注册，若扫描晚于它，目录 Agent 的 `schedules` 不会生效 |

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 一个目录 = 一个 Agent（扫描 · 派生 · 注册 · 失败可见）(Priority: P1)

往 `.oryxos/agents/<name>/` 放一个带 `AGENT.md` 的目录，重启后它出现在 `ProfileRegistry` 里；
frontmatter 被派生成 `Profile`（name 取目录名、`agentDir` 指向该目录）；解析失败的目录不静默消失，
而是进"被跳过清单"并带可读原因。

**Why this priority**: 这是"插件化"的机制本体。没有它，后面所有能力（定时、披露、脚本、记忆）都无从谈起。

**Independent Test**: 不启模型。构造临时目录树 + 临时 Registry，断言加载结果与 `SkippedAgent` 内容；不需要 Spring 上下文。

**Acceptance Scenarios**:

1. **Given** `.oryxos/agents/daily-reconcile/AGENT.md`（合法 frontmatter + 正文），**When** 启动扫描，**Then** Registry 中存在 `daily-reconcile`，其 `agentDir` 指向该目录，`provider`/`tools`/`notify_channels`/`schedules` 与 frontmatter 一致。
2. **Given** 目录名 `Daily_Reconcile`（非法字符），**When** 扫描，**Then** 不注册，且 `skippedAgents()` 有一条 `dirName=Daily_Reconcile`、原因含"目录名不合法"。
3. **Given** 目录里只有 `scripts/` 没有 `AGENT.md`，**When** 扫描，**Then** 不注册，skipped 原因为"缺 AGENT.md"。
4. **Given** frontmatter 缺 `provider`，**When** 扫描，**Then** 不注册，skipped 原因为"缺少必填 provider"，且**其余合法 Agent 照常注册**（单条坏不阻断整体）。
5. **Given** frontmatter 写了 `skills: [x]`，**When** 扫描，**Then** 不注册，skipped 原因点名"AGENT.md 不支持 skills:"。
6. **Given** frontmatter 的 `name` 与目录名不一致，**When** 扫描，**Then** 以目录名为准注册，并 WARN 点名。
7. **Given** 已有 `profiles/daily.yaml` 且存在 `agents/daily/`，**When** 扫描，**Then** agents 版本生效，legacy 版本**连同它的 schedules 一起不注册**，skipped 原因="被 agents/daily 覆盖"。
8. **Given** 扫描先于 `AgentScheduler` 初始化，**When** 应用启动完成，**Then** 目录 Agent 的 `schedules` 已出现在 `GET /api/v1/schedules` 的注册态里。

---

### User Story 2 - 到点自己跑：定时来自 Agent 自己 (Priority: P1)

目录 frontmatter 里声明的 `schedules` 被原样带进派生 Profile；`AgentScheduler` 无需改动语义即可注册；
到点触发走与 CLI/Web 相同的 `AgentService.process`，`agent_runs`/`llm_calls`/`tool_invocations` 正常留账。

**Why this priority**: "会自己跑"是这个需求与"又一个配置格式"的分水岭；也是唯一能证明机制真的串起来的验收点。

**Independent Test**: 用 stub provider + 临时 SQLite 的集成测试（复用 `SchedulerFlowIT` 模式），放一个目录 Agent，手动调 `runOnce` 或推进到触发点，断言 `notify` 被调用且三张审计表有记录。

**Acceptance Scenarios**:

1. **Given** 目录 Agent 声明 `schedules: [{id: reconcile-morning, cron: "0 0 9 * * *", timezone: Asia/Shanghai, message: ...}]`，**When** 注册完成，**Then** `registeredTasks()` 含 `daily-reconcile#reconcile-morning`，下一次触发时刻按 `Asia/Shanghai` 计算。
2. **Given** 同一 Profile 声明两条 schedule，**When** 注册，**Then** 两条都注册；任一 cron 非法只跳过该条，另一条照常（沿用既有"单条隔离"语义）。
3. **Given** 触发生效，**When** 一次运行结束，**Then** `agent_runs` 有 1 条（`channel=scheduler`，`user_message`=schedule 的 message），`tool_invocations` 有对应工具调用记录。
4. **Given** 目录 Agent 没有 `schedules`，**When** 注册，**Then** 不注册任何定时任务，且**不报错**（只是不会自己跑）。

---

### User Story 3 - 渐进式披露：正文常驻，资源按需 (Priority: P1)

`AGENT.md` 正文在每次组装 prompt 时**重新从磁盘读取**并进 system prompt（改完下一轮即生效，不重启）；
`REFERENCE.md`、`skills/*.md`、`scripts/*` **不预载**，由模型按正文指引用 `read_file` / `shell` 按需取。

**Why this priority**: 没有它，"一个目录"就退化成"一份很长的 prompt"，而且会把上下文塞爆。

**Independent Test**: 构造目录 + 假 Session，调 `PromptBuilder.buildSystemPrompt` 断言正文在内、资源不在内；改正文后再次构建，断言取到新内容。

**Acceptance Scenarios**:

1. **Given** 目录 Agent，**When** 组装 system prompt，**Then** `AGENT.md` 正文在其中，且 **frontmatter 的 YAML 文本不在其中**。
2. **Given** 同一 Agent 的 `REFERENCE.md` / `skills/report-format.md` / `scripts/reconcile.py`，**When** 组装 prompt，**Then** 这三者的内容**都不在其中**（不预载）。
3. **Given** 运行中修改 `AGENT.md` 正文，**When** 下一次触发组装 prompt，**Then** 使用新正文（无缓存）。
4. **Given** 目录 Agent，**When** 组装 prompt，**Then** 其中包含一段固定的"资源目录说明"，给出该 Agent 目录的**绝对路径**，并说明脚本与参考文件应使用该绝对路径。
5. **Given** `AGENT.md` 在运行期被删除，**When** 下一次触发，**Then** 该次处理失败并留痕（`agent_runs` 记错误），**不静默回退**到旧内容。
6. **Given** `bootstrap.files` 未声明，**When** 组装 prompt，**Then** 工作区根的 `AGENTS.md`/`SOUL.md`/`USER.md` **不**被注入。
7. **Given** 正文指引运行 `<agentDir>/scripts/reconcile.py`，**When** 模型调用 `shell`，**Then** 脚本**输出**进入上下文，**脚本源码不进入**（下一轮 prompt 中不含 `.py` 正文）。

---

### User Story 4 - 脚本执行边界：只能跑自己目录下的脚本 (Priority: P1)

带 `agentDir` 的 Agent 使用 `shell` 时，命令必须是 `<解释器> <本 Agent 目录下脚本路径> [args]`；
越界、内联代码、shell 元字符串联一律拒绝且不启动任何进程；非 0 退出如实失败；输出有上限。

**Why this priority**: 这是"装一个 Agent = 信任写它的人"这条信任边界的唯一落点。做松了 = 底座对所有 Agent 敞开。

**Independent Test**: `WhitelistSandboxTest` / `ShellToolTest` 覆盖各类命令形态；断言"拒绝时进程数为 0"（用不存在的解释器/脚本做反证）。

**Acceptance Scenarios**:

1. **Given** `agentDir=.../agents/daily-reconcile`，**When** `python <agentDir>/scripts/reconcile.py`，**Then** 执行；`scripts/` 子目录内的脚本同样允许。
2. **Given** 同上，**When** `python ../other/scripts/x.py` 或 `python C:\任意\x.py`，**Then** 拒绝（不可重试），进程未启动。
3. **Given** 同上，**When** `python -c "print(1)"` / `python -m http.server` / `bash -c "..."`，**Then** 一律拒绝。
4. **Given** 同上，**When** `python <script> && del x` / `... | more` / `... > out.txt`，**Then** 拒绝（存在 shell 元字符）。
5. **Given** 脚本以退出码 2 结束，**When** 执行，**Then** `ToolResult.fail(不可重试)`，错误文本包含退出码与输出尾部 ≤2KB。
6. **Given** 脚本输出 1MB，**When** 执行成功，**Then** 回填内容被截断到 32KB 且显式标注"已截断"。
7. **Given** legacy Profile（无 `agentDir`），**When** 执行 `echo hi`，**Then** 沿用旧规则放行（**存量不回归**）。
8. **Given** 沙箱关闭（`oryxos.sandbox.enabled=false`），**When** 任意命令，**Then** 全部放行（开关语义不变，仍打醒目告警）。

---

### User Story 5 - 每个 Agent 有自己的记忆 (Priority: P2)

带 `agentDir` 的 Agent 的长期记忆落在 `<agentDir>/memory/MEMORY.md`，与其他 Agent 及工作区全局记忆互不可见；
`save_memory` / `recall_memory` 与 prompt 注入都自动指向当前 Agent 的那一份。

**Why this priority**: 多个 Agent 共用一份记忆会互相污染（A 的对账结论被 B 当成自己的历史），是"能装 N 个 Agent"的前提。

**Independent Test**: 两个临时 Agent 目录 + 两次处理（切换 `AgentContext`），断言写入互不可见；断言 legacy Profile 仍写全局文件。

**Acceptance Scenarios**:

1. **Given** Agent A 在处理上下文，**When** `save_memory` 写入，**Then** 只有 `<A>/memory/MEMORY.md` 出现该条，B 的文件与全局文件不变。
2. **Given** Agent A 有记忆内容，**When** 组装 prompt，**Then** 注入的是 A 自己的记忆。
3. **Given** legacy Profile（无 `agentDir`），**When** 读写记忆，**Then** 仍落在 `<workspace>/memory/MEMORY.md`（兼容）。
4. **Given** 文件不存在，**When** 首次写入，**Then** 自动创建目录与骨架（沿用既有行为）。
5. **Given** Agent 目录不可写，**When** 写入，**Then** `save_memory` 返回失败结果（而非抛出中断对话）。

---

### User Story 6 - 运行时登记与注销（内核能力，不对外）(Priority: P3)

除启动扫描外，运行期也能登记/注销一个 Agent：两条路径走**同一套校验**（同一异常类型、同一消息）；
注销一个 Agent 时，它的定时任务必须**同时被取消**，不能出现"已经从注册表消失但还在到点跑"的状态。
本节只立内核能力，不开放 HTTP 接口。

**Why this priority**: 30 节要在页面上"新增/删除 Agent"，若内核没有可用的注销语义，那一节还得回头改 `AgentScheduler`；
但它不是本节的验收主线，故列 P3。

**Independent Test**: 临时 Registry + 假 `TaskScheduler`，登记后立即可查；注销后不可查且该 Profile 的所有 future 被 cancel；非法配置在两条路径上抛出的异常类型与消息完全一致。

**Acceptance Scenarios**:

1. **Given** 系统已启动，**When** 运行期登记一个合法 Agent，**Then** 立即可查到它，且（带 `schedules` 时）定时任务已注册。
2. **Given** 运行期注销一个 Agent，**When** 注销完成，**Then** 注册表不再有它，且其**全部** `ScheduledFuture` 被 `cancel(false)`，后续到点不再触发。
3. **Given** 注销后又登记同名 Agent，**When** 重新登记，**Then** 可以再次成功注册定时并触发（旧的 `taskLocks` 条目可复用，不得因残留锁导致新任务永远取不到锁）。
4. **Given** 一份非法配置（provider 未声明），**When** 分别走启动扫描与运行期登记，**Then** 抛出同一异常类型、同一消息。
5. **Given** 注册表中不存在的名字，**When** 注销，**Then** 不抛异常（幂等），但 MUST 打 WARN 点名。

---

### Edge Cases

- **`AGENT.md` 无 frontmatter**：整篇视为正文但缺 `provider` → 硬错跳过（点名"缺少 provider"）。
- **正文为空**：允许注册（只有配置没有指令），WARN 提示"该 Agent 没有正文指令"。
- **目录里只有 `AGENT.md`**（没有 skills/REFERENCE/scripts）：**是最简合法形态**，不得因为"缺资源目录"而跳过或告警为错误。
- **`identity` 同时写成字符串与对象**：非法（YAML 只能有一种），加载失败跳过。
- **`AGENT.md` frontmatter 含未知字段**：**报错跳过**（点名字段名）。理由：Jackson 会静默忽略，`zone` 这类拼写错误会变成"配了没生效"。
- **两个目录同名（不可能）/** **目录名大小写冲突**：Windows 下 `Daily` 与 `daily` 视为同一目录，只可能扫到一个，无需特殊处理。
- **`agents/` 目录不存在**：不报错，等价于"没有目录 Agent"（与 profiles 目录缺失的既有取向一致）。
- **`scripts/` 不存在但正文让跑脚本**：运行时由模型触发失败（工具返回文件不存在），不影响启动。
- **同一个 Agent 的 `schedules` id 与其他 Agent 重名**：不冲突（key 含 profileName，既有语义）。
- **目录 Agent 与 legacy Profile 同名**：agents 赢 + skipped 记录；legacy 的 schedules 不注册（否则同一任务跑两遍）。
- **Agent 名超长或含中文**：按非法目录名跳过并点名（名字会进 URL / session / CLI）。
- **记忆文件是目录**：读取降级为空、写入失败（沿用既有容错）。

---

## Requirements *(mandatory)*

### Functional Requirements

**A. 目录加载与注册**

- **FR-001**: 系统 MUST 扫描 `<workspace>/agents/` 下的**一级子目录**，每个含 `AGENT.md` 的目录派生出一个 `Profile`。
- **FR-002**: `AGENT.md` MUST 拆成 frontmatter（配置）与正文（指令）两部分；frontmatter 用 SnakeYAML 解析、支持 `${ENV_VAR}` 占位（复用既有替换实现），再用 Jackson（SNAKE_CASE）映射为 `Profile`。
- **FR-003**: 派生出的 `Profile.name` MUST 取**目录名**；frontmatter 中的 `name` MUST 被忽略，与目录名不一致时 MUST 打 WARN。
- **FR-004**: `Profile` MUST 新增 `agentDir` 字段（绝对路径），仅由加载器设置；`profiles/*.yaml` 中手写该字段 MUST 被忽略。
- **FR-005**: 目录名 MUST 匹配 `^[a-z0-9][a-z0-9-]*$`；不匹配 → 跳过并进 skipped 清单。
- **FR-006**: 校验 MUST 与 legacy 路径**同一套实现**；同一非法配置 MUST 产生同一异常类型与同一消息。分档如下：`provider.name` 为空、或引用了未注册 provider → **硬错跳过**；声明了未注册的 tool → **仅告警并从可用集合排除，Agent 仍注册**（与 `ToolResolver` 现状一致，见 C-14）。
- **FR-006a**: 缺少 `provider` 段 MUST 视为硬错（C-14）。该规则对 legacy 路径**同步生效**——这是对既有行为的收紧，需在 plan 的兼容性影响中列明。
- **FR-007**: frontmatter 中出现 `skills` MUST 视为硬错（跳过 + 点名），MUST NOT 被映射为 `Profile.skills`。
- **FR-008**: frontmatter 中出现**未知字段** MUST 报错跳过并点名字段名（MUST NOT 静默忽略）。`name` 属已知但被忽略的字段（见 FR-003），不算未知字段。
- **FR-009**: 系统 MUST 维护 `skippedAgents()`：每项含 `dirName` 与 `reason`，一个目录一条（多问题合并），每次全量扫描前重置。
- **FR-010**: 扫描与注册 MUST 早于 `AgentScheduler` 初始化，否则目录 Agent 的 `schedules` 不生效；启动日志 MUST 打印"目录 Agent N 个 / legacy M 个 / 跳过 K 个"。
- **FR-011**: 同名冲突 MUST 由 agents 覆盖 legacy，且 legacy 版本（含其 `schedules`）MUST NOT 注册，并记入 skipped（原因点名）。

**B. 调度**

- **FR-012**: `AgentScheduler` MUST 提供 `registerProfile(Profile)`，`registerAll()` 保留并复用该实现。
- **FR-013**: 注册句柄 MUST 按 `profileName#id` 存入**可按 Profile 检索**的句柄表（注销时按 Profile 批量取消）；既有 `taskLocks` 语义不变（只增不减）。
- **FR-014**: 定时触发的会话身份 MUST 保持 `(channel=scheduler, userId=scheduler, profileName)`；MUST NOT 为目录 Agent 新开渠道。

**C. 上下文与渐进式披露**

- **FR-015**: `ContextLoader` MUST 在每次组装 prompt 时**重新从磁盘读取** `AGENT.md` 正文并剥掉 frontmatter（无缓存）。
- **FR-016**: 正文 MUST 注入 system prompt；frontmatter 文本 MUST NOT 出现在 prompt 中。
- **FR-017**: 目录 Agent MUST NOT 读取工作区根默认 Bootstrap 三件套；显式 `bootstrap.files` MUST 相对 `agentDir` 解析。
- **FR-018**: 组装 prompt 时 MUST 注入一段固定的"资源目录说明"，包含 `agentDir` 的**绝对路径**，并指明脚本/参考文件应使用绝对路径。
- **FR-019**: 正文读取失败（文件缺失/不可读）MUST 使本次处理失败并留痕，MUST NOT 回退到旧内容或空正文。
- **FR-020**: `REFERENCE.md`、`skills/*.md`、`scripts/*` MUST NOT 被预载进 prompt。

**D. 工具边界**

- **FR-021**: `SandboxAction` MUST 可携带当前 `agentDir`；沙箱 MUST 保持无状态（不得依赖 ThreadLocal）。
- **FR-022**: 带 `agentDir` 时，`shell` 命令 MUST 满足：首个 token 在解释器白名单内；第二个 token 解析真实路径后 MUST 位于 `<agentDir>/scripts/` 之内；其余参数原样传递。
- **FR-023**: 带 `agentDir` 时，含 shell 元字符（`&` `|` `;` `>` `<` 等）的命令 MUST 被拒绝；`-c` / `-m` / `-` 形态 MUST 被拒绝。
- **FR-024**: 脚本退出码非 0 MUST 返回 `ToolResult.fail(..., retryable=false)`，错误文本 MUST 含退出码与输出尾部（≤2KB）。
- **FR-025**: shell 输出 MUST 截断至 32KB，截断时 MUST 明确标注已截断。
- **FR-026**: 带 `agentDir` 时，`read_file`/`write_file`/`list_dir` 的文件白名单 MUST 收窄为该 Agent 目录；相对路径 MUST 按 `agentDir` 解析。
- **FR-027**: 无 `agentDir` 的 Profile MUST 沿用既有 shell/文件白名单行为（存量不回归）。
- **FR-028**: `oryxos.sandbox.enabled=false` 的语义 MUST 不变（全放行 + 醒目告警）。

**E. 记忆**

- **FR-029**: 带 `agentDir` 的 Agent 的长期记忆路径 MUST 为 `<agentDir>/memory/MEMORY.md`。
- **FR-030**: `MemoryService` 接口签名 MUST 保持不变；路径解析 MUST 由实现按当前处理上下文（`AgentContext`）完成。
- **FR-031**: 无 `agentDir` 的 Profile MUST 继续使用 `<workspace>/memory/MEMORY.md`。
- **FR-032**: 无处理上下文（如 Web/CLI 直接调用 `snapshot()`）时 MUST 回退到全局记忆文件，MUST NOT 报错。
- **FR-033**: 不同 Agent 的记忆 MUST 互不可见（含 `recall_memory` 与 prompt 注入）。

**F. 安全与可观测性**

- **FR-034**: 启动时若存在带 `scripts/` 的目录 Agent，MUST 打一条醒目 WARN，声明"脚本内容不受本项目限制，可读写文件与联网"。
- **FR-034b**: 上述信任边界 MUST **同时**写进 `quickstart`/文档与启动日志，MUST NOT 只存在于代码注释里。
- **FR-035**: 被跳过的目录 Agent MUST 在启动日志中逐条打印（目录名 + 原因），MUST NOT 只打一行汇总。

**G. 运行时登记与注销（内核能力，不对外接口）**

- **FR-036**: `ProfileRegistry` MUST 提供 `register(Profile)` / `remove(String)` / `exists(String)`，底层仍为并发 Map。
- **FR-037**: 运行期登记 MUST 与启动扫描走**同一段**解析-派生-校验代码；同一非法配置 MUST 产生同一异常类型与同一消息。
- **FR-038**: 注销一个 Profile MUST 同时：从注册表移除、取消该 Profile 的**全部**定时任务、清理其句柄表条目；MUST NOT 出现"注册表里没有但仍在触发"。
- **FR-039**: 注销不存在的名字 MUST 幂等（不抛异常）并打 WARN。
- **FR-040**: 注销后重新登记同名 Agent MUST 能重新注册定时并触发；`taskLocks` 中残留的旧锁 MUST NOT 阻塞新任务。

### Key Entities

- **AgentDirectory**：`agents/<name>/`，含 `AGENT.md`（必需）、`REFERENCE.md`、`skills/*.md`、`scripts/*`、`memory/MEMORY.md`（运行时生成）。
- **AgentDefinition**：解析结果 = frontmatter（Map）+ 正文（String）+ 资源路径（`referenceFile` / `skillsDir` / `scriptsDir`）。
- **Profile（扩展）**：新增 `agentDir`；其余字段语义不变。
- **SkippedAgent**：`dirName`、`reason`（一个目录一条，多问题合并）。
- **ScriptPolicy**：解释器白名单 + `agentDir/scripts` 归属判定 + 元字符/内联形态拒绝规则。

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 放入 1 个合法目录 → 启动后 `ProfileRegistry` 中出现 1 个 Agent，且 `GET /api/v1/profiles` 能看到它（本节不改该端点，靠既有逻辑自然生效）。
- **SC-002**: 放入 N 个目录（含不少于 4 种非法情形）→ 合法者全部注册、非法者全部出现在 `skippedAgents()` 且原因可读，**无一条静默消失**。
- **SC-003**: 目录 Agent 的 `schedules` 到点触发 1 次 → `agent_runs` 新增 1 条、`tool_invocations` 新增 ≥1 条、`llm_calls` 新增 ≥1 条。
- **SC-004**: 修改 `AGENT.md` 正文后**不重启**，下一次触发使用新正文（`ProgressiveDisclosureTest` 断言）。
- **SC-005**: prompt 中 `REFERENCE.md` / `skills/` / `scripts/` 内容出现次数为 **0**（不预载）。
- **SC-006**: 越界脚本形态（跨目录、`-c`、元字符串联）拒绝率 **100%**，且拒绝时启动的进程数为 **0**。
- **SC-007**: 两个 Agent 的记忆文件路径不同且内容互不可见（断言双向）。
- **SC-008**: legacy `profiles/*.yaml` 的既有测试全部通过，`echo` 类旧命令行为不变（**零回归**）。
- **SC-009**: 全仓 `mvn clean verify` **BUILD SUCCESS**。
- **SC-010**: 运行期注销一个带定时的 Agent 后，等待超过其 cron 周期，触发次数为 **0**（注册表与调度器状态一致）。
- **SC-011**: 同一份非法配置经启动路径与运行期路径加载，异常类型与消息**逐字一致**（断言 `getClass()` + `getMessage()` 相等）。

---

## Assumptions

以下为与用户逐条确认的口径（后续 plan/tasks 不得擅自更改）：

| # | 议题 | 定稿 |
|---|---|---|
| A-001 | 与 `profiles/` 的关系 | **并存**，agents 优先；同名时 legacy 不注册并进 skipped |
| A-002 | 对外命名 | 仍叫 **profile**；CLI/API 词汇不变 |
| A-003 | `Profile.agentDir` | **新增**，仅加载器设置，YAML 手写忽略；正文每次读盘 |
| A-004 | 路径基准 | 提示词注入 `agentDir` 绝对路径 + 工具层对相对路径按 `agentDir` 兜底 |
| A-005 | shell 边界 | 按是否有 `agentDir` 分流；有则"解释器 + 本目录 scripts"，无则旧规则 |
| A-006 | 内联代码 | `-c` / `-m` / `-` / `bash -c` 一律拒绝 |
| A-007 | 脚本失败 | 非 0 → `fail(不可重试)`；`retryable` **仅作标记**，不改 ReAct 行为 |
| A-008 | 输出截断 | 32KB |
| A-009 | 运行时注销 | **本节做**内核级 `register` / `unregister`（含取消定时任务），但**不对外暴露接口**；`reload` / 热加载留 30 节 |
| A-010 | skipped 可见性 | 只在 **core** 侧提供数据；Web 端点/CLI 展示留 30 节 |
| A-011 | 隔离口径 | **配置隔离**；脚本内容不限制，可读写文件与联网（启动 WARN 声明） |
| A-012 | frontmatter schema | 以代码 `Profile` 为准（C-1），仅 `identity` 接受对象形态 |
| A-013 | `skills/` | 目录资源，不映射 `Profile.skills`；frontmatter 写 `skills:` = 硬错 |
| A-014 | Bootstrap | 目录 Agent 不读默认三件套，除非显式声明 |
| A-015 | 记忆 | 每 Agent 一份 `<agentDir>/memory/MEMORY.md`；legacy 回退全局文件 |
| A-016 | 示例数据 | `daily-reconcile/samples/` 放两份 CSV，脚本默认读 samples、环境变量可覆盖 |
| A-017 | 示例位置 | `daily-reconcile/` 放 `.oryxos/agents/` 下（会被真扫到、按 cron 真跑；缺数据失败可接受） |
| A-018 | 人工验收 | 用 `weather` Agent + `deepseek-flash` + 既有 `tools/webhook_sink.py:9099`；真模型链路**人工验证** |
| A-019 | 校验严格性 | 未知字段/非法目录名/缺 provider/含 `skills` 一律**硬错跳过**，不做"部分生效" |
| A-020 | skipped 生命周期 | 每次全量扫描**重置**（与 scheduler 的"只增不减"不同） |
| A-021 | 缺 `provider` | **硬错跳过**，且对 legacy 路径**同步生效**（属对既有"WARN 仍注册"行为的收紧，plan 需列兼容性影响） |

---

## 交付物（供 plan/tasks 拆解）

**代码**

- `AgentLoader`（解析目录 → frontmatter/正文/资源路径）
- `deriveProfile(agentDir) → Profile`
- 启动扫描装配（`.oryxos/agents/`，早于 `AgentScheduler`）
- `Profile` + `agentDir`
- `ProfileRegistry`：`register` / `remove` / `exists`（命名对齐 + 运行时增删）
- `ContextLoader` 注入目录正文 + 资源目录说明
- `AgentScheduler.registerProfile` + `unregisterProfile`（按 Profile 取消定时）+ 可按 Profile 检索的句柄表
- `SandboxAction` + `WhitelistSandbox`（脚本目录段）
- `ShellTool`（退出码 / 截断 / agentDir）
- `FileTool`（相对路径 + 目录收窄）
- `DefaultMemoryService`（按 `AgentContext` 解析记忆路径）
- `SkippedAgent` + `skippedAgents()`

**示例**

- `.oryxos/agents/daily-reconcile/`：`AGENT.md`、`scripts/reconcile.py`、`skills/report-format.md`、`REFERENCE.md`、`samples/*.csv`

**测试（harness）**

- `AgentLoaderTest`、`DeriveProfileTest`、`AgentScanRegisterTest`、`ProfileRegistryRuntimeTest`、`AgentSchedulerRegisterTest`
- `AgentSchedulerUnregisterTest`（注销取消全部 future；同名重登记可再触发；幂等注销）
- `ProgressiveDisclosureTest`（正文进 prompt、资源不预载、改正文即时生效）
- `ScriptPolicyTest`（越界/内联/元字符/退出码/截断）
- `PerAgentMemoryTest`（隔离 + legacy 回退）
- `AgentScanFlowTest`（stub provider + 临时 SQLite：目录 Agent 触发 → notify → 审计三表）

---

## 验收结果（2026-10-30）

| 项 | 结果 |
| --- | --- |
| 真模型端到端 | ✅ 用户以真实模型验证通过：取天气 → 写记忆 → webhook 推送 |
| 自动化测试 | ✅ core 115 / memory 47 / tool 132 / storage 14 / web 61 / channel 2 / cli 20 / boot 13，全量绿 |
| 启动时序 | ✅ 日志确认 `profiles → agents → registry → scheduler`：目录 Agent 2 个、定时任务 2 条 |
| 文档 | ✅ `quickstart.md` 补齐（目录结构、字段表、脚本边界、信任边界、验收清单） |
| 遗留 | `AgentScanFlowTest`（stub provider 的自动集成）以"真实启动冒烟"替代；`skippedAgents` 的 Web/CLI 透出按约定留 30 节 |
