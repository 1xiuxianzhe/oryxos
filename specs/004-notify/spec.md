# Feature Specification: Notify 出站通知模块

**Feature Branch**: `004-notify`

**Created**: 2026-10-09

**Status**: Implemented — 已按 `tasks.md` 交付并通过全部自动化验证（32 例）与人工验收；
已知边界（域名白名单纯占位、不接 IM 专用 API、不含定时触发）见 `quickstart.md` 第 7 节

**Input**: User description: "docs/class/第19节：Notify 模块 原理解析、实现与代码讲解.md —— 入站有 Channel 负责『消息怎么进来』，Notify 补的是对称的另一半『结果怎么主动送出去』"

**Source document**: `docs/class/第19节：Notify 模块 原理解析、实现与代码讲解.md`

## 背景与定位

CLI（003）和 Web Service 都是"人推"：有人发起调用、Agent 处理完把响应原路返回，不需要额外的推送机制。一旦触发源变成"到点自动"（定时查天气、每日汇总），这条链路就断了——没有人在另一端等响应，Agent 必须自己决定**把结果送到哪、怎么送**。

本特性补的是这个出口：把"往外推一条消息"这件事从各业务 Skill 里收拢成统一能力，供后续定时模块与日报类 Agent 使用。

**边界声明**：本特性只交付**出站通知**能力，不交付定时触发、不交付多渠道管理、不交付专用 IM SDK。企业微信/飞书/钉钉的签名算法、AccessToken 刷新等认证细节属扩展阶段；核心阶段用一个通用 webhook 覆盖群机器人场景。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 把一条内容推送到配置好的 webhook (Priority: P1)

业务方在 Profile 里配好一个通用 webhook 地址（企业微信/飞书/钉钉群机器人都提供这种地址），系统能把一段文本内容 POST 过去。调用方只管"要发什么内容"，不需要知道发到哪个 URL、用什么协议。

**Why this priority**: 这是整个模块的地基。没有它，后面所有"主动通知"都无从谈起；有了它，即使不接任何 Agent，也能被其它代码直接复用来推送。

**Independent Test**: 在本地起一个假 webhook 服务，构造一个带 `url` 的 `NotifyTarget`，调用发送，断言假服务收到了 POST、请求体里带 `content`、URL 来自 target 而非硬编码。整个测试不离线、不依赖外网。

**Acceptance Scenarios**:

1. **Given** 一个 `channelType=webhook` 且 `config.url` 指向本地假服务的通知目标，**When** 发送内容"hello"，**Then** 假服务收到一个 POST 请求，请求体包含 `"content":"hello"`。
2. **Given** 同一个目标但换成另一个 URL 的假服务，**When** 发送，**Then** 请求到达的是新 URL（证明地址来自配置而非写死）。
3. **Given** 假服务返回 5xx，**When** 发送，**Then** 调用方收到异常（非静默成功）。

---

### User Story 2 - Agent 在对话中主动推送 (Priority: P1)

用户在对话里说"把『测试消息』推送一下"，Agent 自己决定调用 `notify` 工具，系统从**当前 Profile** 的 `notify_channels` 里解析出目标渠道并把消息推出去，然后告诉用户已推送。

**Why this priority**: 这是把 US1 的能力真正接到 Agent 身上的那一环，也是"接口先行"设计的验证点——调用方（Agent）完全不需要知道渠道细节。

**Independent Test**: 构造一个已配好 `notify_channels` 的 Profile 放进处理上下文，用替身 adapter 调用 `notify`，断言渠道被正确解析、内容被原样传出；再构造未配置渠道的 Profile，断言明确报错而不是静默成功。

**Acceptance Scenarios**:

1. **Given** 当前 Profile 配了 1 个 webhook 渠道，**When** Agent 调用 `notify(content="测试消息")` 且不传 `channel`，**Then** 使用该渠道推送成功，返回成功结果。
2. **Given** 当前 Profile 配了多个渠道，**When** 不传 `channel`，**Then** 使用**第一个**渠道（对应"大多数情况 LLM 只传 content 就够"）。
3. **Given** 当前 Profile 没有配置任何 `notify_channels`，**When** Agent 调用 `notify`，**Then** 返回失败结果并给出明确原因（Agent 不会误以为发出去了）。
4. **Given** `notify` 已注册为内置工具，**When** Agent 组装本轮可用工具列表，**Then** 列表里包含 `notify` 且带有可被 LLM 理解的入参 schema（`content` 必填、`channel` 可选）。
5. **Given** 用户显式指定了一个不存在的渠道名，**When** 调用，**Then** 返回失败结果并指出该渠道名未配置。

---

### User Story 3 - 出站请求先过白名单再发送 (Priority: P2)

`notify` 发出去的是一次 HTTP 请求，理应和 `http_post` 一样过一层域名白名单——不能因为它是"往外推"就绕过去。系统在校验通过前**不得**发出任何网络请求。

**Why this priority**: 安全底线。顺序错（先发后校验）等于没有校验，因此这条要求必须能被测试钉死，而不是靠代码评审目测。

**Independent Test**: 用替身记录调用顺序，断言"校验"先于"发送"；再让校验抛异常，断言 adapter 完全没被调用。

**Acceptance Scenarios**:

1. **Given** 已装配域名校验器，**When** 调用 `notify` 且校验通过，**Then** 调用顺序为「校验 → 发送」。
2. **Given** 校验判定目标 URL 不在白名单内，**When** 调用 `notify`，**Then** 抛出/返回违规失败，且 adapter **一次都没有被调用**。
3. **Given** 尚未装配域名校验器（本特性交付时的占位状态），**When** 调用 `notify`，**Then** 行为仍可继续（放行）但必须留下告警日志，明确提示"出站白名单未生效"。

---

### User Story 4 - 通知地址属于敏感信息，不得泄漏 (Priority: P3)

群机器人 webhook 地址通常内嵌凭证（例如企业微信的 `...?key=<密钥>`）。系统在展示 Profile 详情、输出错误信息、写日志时都不得把完整地址或凭证打出来。

**Why this priority**: 泄漏一个 webhook 地址等于把公司群组的推送权限交出去。属安全要求，但优先级低于"功能跑通"。

**Independent Test**: 在 Profile 里配一个带 `key=` 的 webhook 地址，执行 Profile 详情展示，断言输出中不出现该地址或 key；构造一次发送失败，断言错误信息里也不出现完整地址。

**Acceptance Scenarios**:

1. **Given** Profile 的 `notify_channels` 里配了 `https://example.com/hook?key=SECRET123`，**When** 用户查看 Profile 详情，**Then** 输出中不出现 `SECRET123`，也不出现完整 URL。
2. **Given** 一次推送因网络错误失败，**When** 错误被返回/记录，**Then** 错误信息中不包含完整 webhook 地址（只保留域名或脱敏形式）。

---

### Edge Cases

- **渠道类型未知**：Profile 里配了 `type: feishu`（核心阶段未实现的渠道）→ 必须明确报"不支持的渠道类型"，不能静默用 webhook 处理。
- **配置缺 `url`**：`type: webhook` 但 `config` 里没有 `url` → 明确报配置错误，不发请求。
- **URL 非法**：`url` 不是合法地址（无 scheme/host）→ 在校验阶段失败，不发请求。
- **内容为空**：`content` 为空或纯空白 → 明确拒绝（推送空消息无意义，且易掩盖上游 Bug）。
- **网络超时**：目标 webhook 长时间无响应 → 按可重试语义返回失败，不无限等待。
- **同一 Profile 多渠道**：`channel` 缺省取第一个；显式指定时按 `type` 或序号匹配（匹配规则见 `research.md`）。
- **白名单未装配**：见 US3 场景 3。

## Requirements *(mandatory)*

### Functional Requirements

**渠道抽象（接口先行）**

- **FR-001**: 系统 MUST 提供一个**不携带任何渠道特有词汇**的通知发送抽象，表达"把一段内容送到某个通知目标"这一意图；接口签名中不得出现具体渠道名称、具体协议字段。
- **FR-002**: 系统 MUST 用独立的数据载体描述"通知目标"，包含渠道类型与一份渠道特定配置，具体字段含义由实现类自行解释。
- **FR-003**: 系统 MUST 提供 core 阶段唯一的一档实现：通用 webhook 推送（HTTP POST JSON）。新增渠道 MUST 只新增实现类，不得修改 FR-001 的接口、也不得修改调用方。

**工具接入**

- **FR-004**: 系统 MUST 提供一个名为 `notify` 的内置工具，入参为「内容」与可选的「渠道」，供 LLM 通过现有工具调用机制使用。
- **FR-005**: `notify` MUST 从**当前正在处理的 Profile** 解析通知目标，而不是从对话内容或全局配置解析；Profile 的解析来源 MUST 复用现有的处理上下文机制。
- **FR-006**: 当未显式指定渠道时，`notify` MUST 使用 Profile 中配置的**第一个**通知渠道。
- **FR-007**: 当 Profile 未配置任何通知渠道时，`notify` MUST 返回失败结果并说明原因；不得静默成功、不得伪造"已推送"。
- **FR-008**: 当显式指定的渠道不存在、或渠道类型不受支持时，`notify` MUST 返回失败结果并指明具体原因。
- **FR-009**: `notify` MUST 通过**既有工具抽象**接入（获取名称/描述/入参 schema/执行），不得引入第二套工具注册或执行机制。
- **FR-010**: `notify` MUST 在应用启动时被注册进工具注册表，使 Agent 组装 Prompt 时能把它列为可用工具。

**安全与失败语义**

- **FR-011**: `notify` 在真正发出 HTTP 请求**之前** MUST 先执行目标地址的安全校验；校验未通过时 MUST NOT 发出任何网络请求。
- **FR-012**: 当安全校验器尚未装配时，系统 MUST 明确记录"出站白名单未生效"的告警，而不是静默跳过。
- **FR-013**: 通知发送失败（网络错误、超时、目标返回错误状态）MUST 向上暴露为失败，不得被静默吞掉或改写成成功。
- **FR-014**: 系统 MUST 在展示 Profile 详情与错误信息时对通知地址做脱敏，不得输出完整 webhook 地址或其内嵌凭证。

**配置**

- **FR-015**: 通知渠道 MUST 通过 Profile 的配置字段声明，而非写死在代码或对话中；配置结构 MUST 与既有 Profile 数据模型保持一致。
- **FR-016**: Profile 加载时 MUST 对通知渠道配置做合法性校验（渠道类型是否受支持、必需配置项是否齐备）；校验失败的 Profile 按既有容错策略处理，不阻断其它 Profile。

### Key Entities

- **通知目标（NotifyTarget）**：一次推送的"寄送地址"。含渠道类型 + 一份键值形式的渠道配置。是对外抽象里唯一携带"去哪儿"语义的载体。
- **通知渠道适配器（NotifyChannelAdapter）**：把"内容 + 目标"变成实际外发动作的抽象。core 阶段只有 webhook 一档实现。
- **Notify 工具（notify）**：LLM 可调用的入口。负责「解析目标 → 安全校验 → 发送 → 返回结果」这条固定顺序。
- **Profile.notify_channels**：Profile 中的通知渠道配置列表（**已存在于现有数据模型**，本特性开始真正消费它）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 配好 webhook 渠道后，Agent 能在一次对话中成功把指定内容推送到该地址——人工验收用一个**接受 `{"content":"..."}` 的通用端点**（自建接收器 / n8n / 中间层）确认收到该 JSON。（不适用 IM 官方机器人，原因见 A-010）
- **SC-002**: 从"新增一档渠道实现"的角度衡量接口中立性：新增一个渠道（例如企业微信专用实现）只需要新增一个实现类，`NotifyChannelAdapter` 的签名与调用方代码**零改动**。（思维练习，无法自动化）
- **SC-003**: 未配置渠道、渠道名不存在、渠道类型不支持、配置缺 `url`、内容为空这 5 类错误，**100% 返回明确失败**，无一例静默成功。
- **SC-004**: 安全校验与发送的调用顺序在自动化测试中被固定为「校验先于发送」；校验失败时外发调用次数为 **0**。
- **SC-005**: 默认测试套件不依赖外网、不需要 API key，`mvn test` 可重复全绿。
- **SC-006**: Profile 详情输出与错误信息中，webhook 地址与内嵌凭证的出现次数为 **0**。

## Assumptions

以下为源文档（教学示意代码）与仓库实际实现的差异，本规格以**仓库实际架构**为准，逐条记录取舍：

- **A-001 处理上下文**：源文档用 `ProfileContext.resolveNotifyChannel(channel)`，仓库真实存在的是 `AgentContext`（静态 ThreadLocal，提供 `current()` 返回当前 Profile，且**没有** `resolveNotifyChannel` 方法）。因此渠道解析逻辑需要新增，放在工具模块而非 core（core 不得依赖工具模块的类型）。
- **A-002 工具机制**：源文档用 Spring AI 的 `@Tool` + `@Component` 自动装配；仓库统一使用 `OryxTool` 抽象（`getName/getDescription/getInputSchema/execute`），且 `ToolRegistry` **没有任何 Spring 自动收集机制，只有手动 `register()`**。按 Constitution 原则 III/V，本特性遵循仓库既有机制。
- **A-003 安全校验**：源文档用尚不存在的 `Sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url))`；仓库真实的等价物是 `SandboxChecker.checkHttpUrl(url)`。注意其语义：**白名单为空时 `anyMatch` 恒为 false，即"全拒"**。为兼顾"先占位、细节留给 Sandbox 特性"，本特性把校验器做成**可选依赖**（未装配时放行并告警），而不是强制要求配置，否则开箱即用会全部被拒。
- **A-004 HTTP 客户端**：源文档用 Spring `RestClient`；仓库既有 HTTP 工具用 JDK `java.net.http.HttpClient`，且工具模块的类路径上未必有 `spring-web`。本特性沿用 JDK `HttpClient`，不新增依赖。
- **A-005 测试假服务**：源文档要求 MockWebServer；仓库从未引入该依赖（会连带 okhttp/kotlin）。本特性改用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 起本地假 webhook——同样是本地、离线、单测层，且**零新增第三方依赖**。
- **A-006 配置结构**：源文档的 YAML 示例把 `url` 平铺在渠道下（`- type: webhook` + `url: ...`）；仓库现有 `Profile.NotifyChannelConfig` 的结构是 `type` + 嵌套 `config` 映射。本特性沿用**已有结构**，不改数据模型。配置示例见 `quickstart.md`。
- **A-007 与后续章节的关系**：源文档说明 `NotifyTools` 的完整接线依赖"20 节的 `@Tool`/`ToolResult`"与"23/24 节的 Sandbox"。经核查，`ToolResult`、`ToolRegistry`、`SandboxChecker` 在仓库中**已存在**，因此本特性直接接线完成，不留给后续章节；真正的后续工作是**统一 Sandbox 设计**（Path/Shell/HTTP 三类白名单、配置来源、强制启用），那部分不在本特性范围。
- **A-008 内置工具注册现状**：`ToolRegistry` 当前启动时为空，`FileTool`/`ShellTool`/`HttpTool` 均未被实例化注册。本特性只注册 `notify` 一个工具（净增能力、无回归）；把其余内置工具接入注册表是**既有缺口**，不在本特性范围。
- **A-009 工具清单一致性**：`oryxos-web` 的工具列表接口当前硬编码了 8 个工具名，本特性注册 `notify` 后会出现不一致。按最小改动原则，本特性仅同步该清单，不改造其数据来源。
- **A-010 通用 webhook 与 IM 官方机器人不兼容（重要）**：源文档称"企业微信、飞书、钉钉的群机器人都提供 webhook 地址，核心阶段用一个通用的 `WebhookNotifyAdapter` 就能覆盖大部分场景"，但其示例代码发送的是 `{"content": content}`。实际三家官方机器人要求的结构各不相同（企业微信/钉钉为 `{"msgtype":"text","text":{"content":"..."}}`，飞书为 `{"msg_type":"text","content":{"text":"..."}}`），**本特性交付的通用 webhook 无法直接推送到这三家**。此外这三家在业务出错时**仍返回 HTTP 200**，错误码位于响应体（`errcode` / `code`），因此"2xx 即成功"的判定对它们还会误报成功。
  **本特性取舍**：仍按源文档交付通用 webhook（面向自建接收器 / n8n / 中间层这类接受 `{"content":...}` 的端点），不新增厂商专用适配器——源文档明确把签名算法与 AccessToken 刷新划归扩展阶段。接官方机器人需要中间层翻译，或后续新增专用适配器。该限制已写入 `quickstart.md` 第 5 节。
