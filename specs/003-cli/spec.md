# Feature Specification: CLI 命令行入口与会话管理

**Feature Branch**: `003-cli`

**Created**: 2026-09-28

**Status**: Draft

**Input**: 用户描述："参考 `docs/class/第18节：CLI 功能概述、实现思路与代码讲解.md`，完成对应的 specify 文档"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 初始化并查看 OryxOS 工作区 (Priority: P1)

用户在项目目录中执行初始化命令，获得可直接编辑和运行的 OryxOS 工作区；之后可以查看当前 Profile、Provider、Tool、Session 和运行状态，不需要先启动模型服务。

**Why this priority**: 这是用户第一次使用 OryxOS 的入口，也是后续对话和服务运行的前置条件。状态查询必须足够轻量，才能支持快速诊断。

**Independent Test**: 在一个空目录执行初始化，再执行状态和各类列表命令，验证工作区结构、默认 Profile 和可读的查询结果均已生成。

**Acceptance Scenarios**:

1. **Given** 当前目录尚未初始化，**When** 用户执行 `oryxos init`，**Then** 系统创建 `.oryxos/` 工作区、必要子目录、Bootstrap 文件、记忆文件、配置文件和默认 Profile。
2. **Given** 当前目录已经初始化，**When** 用户再次执行 `oryxos init`，**Then** 系统不覆盖用户已有配置或历史数据，并给出明确的已初始化提示。
3. **Given** 工作区存在一个或多个 Profile，**When** 用户执行 `oryxos profile list`，**Then** 系统列出 Profile 名称及可识别的基本状态。
4. **Given** 用户执行 `status`、`provider list`、`tool list` 或 `session list`，**When** 对应数据可用或为空，**Then** 系统分别输出可读结果，不因空列表报错。

---

### User Story 2 - 通过命令行完成多轮 Agent 对话 (Priority: P1)

用户可以在终端选择一个 Profile 与 Agent 进行多轮对话。CLI 读取用户输入，将消息交给统一的 Agent 引擎，再把最终回复打印出来；CLI 本身不实现推理、模型调用或工具执行。

**Why this priority**: CLI 是 Provider 和 ReAct 能力第一次对用户可见的入口，直接验证 OryxOS 是否能够完成最短可用链路。

**Independent Test**: 启动 `oryxos chat`，输入至少两条消息并观察回复；再使用指定 Profile 启动一次，并通过退出命令结束会话。

**Acceptance Scenarios**:

1. **Given** 默认 Profile 可用，**When** 用户执行 `oryxos chat`，**Then** 系统进入交互模式并使用默认 Profile 创建或恢复 CLI Session。
2. **Given** 用户指定了存在的 Profile，**When** 用户执行 `oryxos chat --profile <name>`，**Then** 对话使用指定 Profile，而不是默认 Profile。
3. **Given** 用户输入一条普通消息，**When** CLI 收到该输入，**Then** 消息被交给统一 Agent 服务处理，并将最终回复输出到终端；CLI 不重复执行模型或 Tool 逻辑。
4. **Given** 用户连续输入多条消息，**When** 每条消息处理完成，**Then** 它们在同一个 Session 中按发生顺序保留，上下文可用于后续回复。
5. **Given** 用户输入 `/quit`，**When** CLI 读取该命令，**Then** 当前交互正常结束并返回进程控制权，不把 `/quit` 发送给 Agent。
6. **Given** Profile 不存在、模型调用失败或处理超时，**When** CLI 收到引擎错误，**Then** CLI 输出简洁、可读且不泄露密钥的错误信息，并以非成功状态结束当前单次处理或命令。

---

### User Story 3 - 管理 Profile 并查看详情 (Priority: P2)

用户可以通过命令行创建、查看和删除 Profile，以便在同一个 OryxOS 实例中管理多个 Agent 配置。

**Why this priority**: 多 Profile 并存是 OryxOS 作为 Agent OS 的最小体现，也是 `chat --profile` 能够选择不同 Agent 的基础。

**Independent Test**: 创建一个新 Profile，查看其详情，确认其出现在列表中，再删除它并确认查询结果更新。

**Acceptance Scenarios**:

1. **Given** 工作区已初始化且目标名称未占用，**When** 用户执行 `oryxos profile create <name>`，**Then** 系统生成该名称的 Profile 配置模板，并提示用户后续可编辑的配置位置。
2. **Given** Profile 已存在，**When** 用户执行 `oryxos profile show <name>`，**Then** 系统展示其身份、Provider、Tool、Channel 和运行参数等可公开配置内容。
3. **Given** Profile 已存在且允许删除，**When** 用户执行 `oryxos profile delete <name>`，**Then** 系统删除或标记删除该 Profile，并保留与其关联的历史 Session 不被误删。
4. **Given** 用户对不存在的 Profile 执行创建冲突、查看或删除操作，**When** 命令运行，**Then** 系统返回明确错误和下一步提示，不产生部分配置文件。

---

### User Story 4 - 以不同模式运行 OryxOS (Priority: P2)

用户可以选择交互式对话、HTTP 服务或常驻 Gateway 模式运行 OryxOS。不同入口共享同一份 Profile 配置和 Session 数据，Agent 逻辑不因入口不同而产生分叉。

**Why this priority**: CLI 不只是聊天命令，还承担本地服务启动和运行模式切换，是 OryxOS 对外提供能力的统一启动入口。

**Independent Test**: 分别启动 `serve` 和 `gateway` 的帮助或启动流程，确认它们使用统一配置；在不同模式下读取同一个已存在 Session，验证数据来源一致。

**Acceptance Scenarios**:

1. **Given** 服务配置有效，**When** 用户执行 `oryxos serve`，**Then** 系统启动 HTTP 服务并使用默认端口或用户指定的端口提供服务。
2. **Given** Gateway 所需配置有效，**When** 用户执行 `oryxos gateway`，**Then** 系统进入常驻运行模式，并挂载当前核心阶段支持的消息入口。
3. **Given** 用户在 `chat`、`serve` 或 `gateway` 模式下使用同一 Profile，**When** 读取或追加 Session，**Then** 三种模式使用同一套 Profile 和 Session 存储约定。
4. **Given** 启动模式配置缺失或非法，**When** 用户启动命令，**Then** 系统在启动前给出明确诊断，不进入半可用状态。

### Edge Cases

- 工作目录不可写、目标文件已存在或配置文件格式非法时，初始化和 Profile 操作必须报告具体路径及原因，不覆盖已有数据。
- `chat` 从标准输入读取到 EOF、空行或只有空白的输入时，不应崩溃；空行不得被误当成有效用户问题提交。
- 指定不存在的 Profile、Provider 或 Tool 时，命令必须返回非成功结果，并指出对应名称。
- 用户在 Agent 处理期间中断进程时，已产生的 Session 历史仍应尽可能保存，不能因 CLI 退出而主动清空历史。
- Session 历史包含工具调用消息、错误消息或较长内容时，列表和详情输出必须保持可读，不泄露 API key、完整系统提示词或其他敏感配置。
- 已初始化目录重复执行 `init`、重复创建 Profile、删除默认 Profile 等操作必须有明确的保护或提示行为。
- 轻命令依赖的工作区不存在时，应直接提示先执行初始化；不应为了查询空目录强制启动完整运行时。
- 重命令启动失败时，应返回可识别的失败状态，并避免留下无法继续使用的半初始化进程。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供统一的 `oryxos` 命令入口，并支持 12 个核心命令：`init`、`status`、`chat`、`serve`、`gateway`、`profile list`、`profile create`、`profile show`、`profile delete`、`provider list`、`tool list`、`session list`。
- **FR-002**: 每个核心命令 MUST 提供可发现的用途说明、参数说明、示例或默认值，并在参数非法时返回清晰错误。
- **FR-003**: 系统 MUST 通过命令参数支持为 `chat` 选择 Profile；未指定时 MUST 使用 `default` Profile。
- **FR-004**: `chat` MUST 支持交互式读取标准输入、输出提示符和 Agent 最终回复，并支持 `/quit` 正常退出。
- **FR-005**: CLI MUST 将用户消息交给统一的 Agent 服务处理，不得在命令层重复实现 ReAct、Provider 调用、Tool 调度或 Memory 逻辑。
- **FR-006**: CLI MUST 为一次交互获取或创建由 `channel`、`user`、`profile` 确定的 Session，并在同一组三元组下重复获取时返回同一会话。
- **FR-007**: Session 标识的生成 MUST 只由 Session 管理能力负责；CLI、Web 或其他入口不得各自拼接 Session ID。
- **FR-008**: Session MUST 持久化会话元数据与按顺序排列的消息历史，消息内容至少能区分用户、助手和工具角色。
- **FR-009**: Session 存储 MUST 支持通过新建运行上下文重新读取历史，使 OryxOS 重启后仍能恢复已保存的会话。
- **FR-010**: 当 `channel`、`user` 或 `profile` 任一值不同时，系统 MUST 创建或读取不同的 Session，避免不同入口、用户或 Agent 之间串话。
- **FR-011**: `init` MUST 在当前工作目录创建 OryxOS 工作区及核心目录、Bootstrap 文件、长期记忆文件、Profile 目录、Session 存储位置和默认 Profile。
- **FR-012**: `init` MUST 是幂等的；重复执行时不得覆盖已有 Profile、Bootstrap、Memory 或 Session 数据。
- **FR-013**: Profile 管理命令 MUST 支持创建、列出、查看详情和删除 Profile，并对名称重复、不存在或不合法的情况给出明确失败结果。
- **FR-014**: Profile 的删除操作 MUST 不得自动删除该 Profile 已产生的 Session 历史或审计记录。
- **FR-015**: 系统 MUST 提供 `status`、`provider list`、`tool list` 和 `session list` 查询能力；无数据时返回空结果说明而非异常堆栈。
- **FR-016**: 系统 MUST 将命令分为轻命令和重命令：不需要模型或 Agent 引擎的查询与文件操作命令应快速返回；需要运行 Agent 或服务的命令才加载完整运行能力。
- **FR-017**: `serve` MUST 启动 HTTP 服务，支持默认端口并允许用户通过命令配置覆盖；具体 REST 资源由 Web Service 规格定义。
- **FR-018**: `gateway` MUST 启动常驻运行模式，并复用统一的 Profile、Session 和 Agent 服务；核心阶段不得为 Gateway 另写一套 Agent 处理逻辑。
- **FR-019**: 重命令启动后 MUST 能正常使用 Session 持久化和审计能力；跨模块的配置、实体和数据访问不得导致命令启动失败或数据静默丢失。
- **FR-020**: CLI 输出 MUST 默认隐藏 API key、Provider 凭证、完整系统提示词和其他敏感配置；错误信息 MUST 包含足以定位问题的命令、资源名称或路径。
- **FR-021**: CLI MUST 将模型、工具和 Session 层抛出的失败以可读错误传递给用户，不得用伪造的正常回复掩盖失败，也不得静默吞掉异常。
- **FR-022**: Session 持久化失败时，CLI MUST 返回失败状态并输出持久化原因；已产生的原始处理错误不得被无关的保存错误静默替换。
- **FR-023**: CLI 运行过程 MUST 输出必要的结构化运行日志，至少能关联命令、Profile、Session 和处理结果；日志不得包含敏感凭证或完整系统提示词。
- **FR-024**: 核心阶段 MUST 保持 CLI 为薄入口，不实现认证、IM Channel、SSE 流式输出、WebSocket、复杂任务编排或 Agent 间委托。

### Key Entities *(include if feature involves data)*

- **CLI Command**: 一个可执行的用户操作，包含名称、用途、参数、运行模式和退出结果。
- **Workspace**: OryxOS 在当前项目目录下的工作区，包含 Profile、Bootstrap、Memory、Skill、Session、日志和配置数据。
- **Profile**: 一个 Agent 的可运行配置，包含名称、身份设定、Provider、Tool、Channel、Bootstrap 和运行参数。
- **Session**: 用户与某个 Profile 在某个渠道中的连续对话容器，包含唯一标识、消息历史、状态和生命周期时间。
- **Message**: Session 中按顺序保存的一条用户、助手或工具消息。
- **Run Mode**: `chat`、`serve` 或 `gateway` 之一，表示消息进入 OryxOS 的方式。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 新用户在一个空目录中执行初始化后，能够在 1 分钟内完成工作区创建并看到默认 Profile。
- **SC-002**: 12 个核心命令均能通过帮助信息发现其用途和参数；命令参数错误时 100% 返回可读提示和失败状态。
- **SC-003**: 用户通过 `chat` 完成至少 2 轮连续对话时，100% 的消息按原始顺序保存在同一个 Session 中，且 `/quit` 能正常结束交互。
- **SC-004**: 同一 `channel + user + profile` 的重复会话获取 100% 返回同一 Session；三元组任一字段不同的会话 100% 相互隔离。
- **SC-005**: 已保存的 Session 在重新启动运行上下文后 100% 能读回，历史消息、角色和顺序保持不变。
- **SC-006**: 轻命令在常规本地环境下无需等待完整运行时启动即可返回结果；其响应时间目标不超过 1 秒，不包含文件系统异常和操作系统首次启动开销。
- **SC-007**: 通过 `chat`、`serve`、`gateway` 使用同一 Profile 时，三种模式读取同一套配置和 Session 数据，不产生入口专属的重复历史。
- **SC-008**: Profile 创建、查看、列表和删除的成功与失败场景均有明确终端反馈，测试用例覆盖率达到 100%。
- **SC-009**: CLI 端到端测试至少覆盖初始化、Profile 管理、双轮对话、Session 重启恢复、轻重命令分流和命令帮助六类流程，并在无真实模型网络依赖时可重复执行。
- **SC-010**: 典型 CLI 错误输出中不出现 API key、凭证、完整系统提示词或其他敏感配置；人工检查和自动化脱敏测试均通过。

## Assumptions

- `oryxos-core` 已提供可调用的 Agent 服务、Profile、Session 和消息抽象；本功能负责把它们接入 CLI，不重新实现 Agent 内核。
- 核心阶段的命令行实现沿用项目既定的命令行交互约定；规格关注用户可见行为和边界，不定义具体命令类的包结构。
- 工作区默认位于当前目录的 `.oryxos/`，用户可以通过既有配置机制覆盖工作区位置。
- Session 的关系型持久化、工具调用审计和 LLM 调用审计沿用项目现有存储方案；本功能至少保证 Session 可保存和恢复，不新增审计查询界面。
- `serve` 的 REST 端点、`gateway` 的多渠道扩展和 Provider/Tool 的具体实现由对应功能规格负责；本功能只定义 CLI 启动和复用边界。
- 核心阶段默认不启用认证、IM Channel、流式响应、WebSocket、复杂编排、并行 Tool 或 Agent 间委托。
- 退出码遵循常见命令行约定：成功操作返回成功状态，参数错误、配置错误、运行时错误或持久化错误返回非成功状态。
- 轻命令不启动完整 Spring 上下文，重命令按需启动完整运行上下文；跨模块扫描和数据存储必须在重命令启动时可用。
