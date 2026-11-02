# Feature Specification: Web Service 与第一版管理平台

**Feature Branch**: `009-web-admin`

**Created**: 2026-10-21

**Status**: Draft

**Input**: User description: "docs/class/第26节：Web Service 与第一版管理平台 实现与代码讲解.md —— 把五大能力包装成 REST API，顺带做出只读管理平台；前端风格参考给定截图，清晰简洁"

**Source documents**:

- `docs/class/第26节：Web Service 与第一版管理平台 实现与代码讲解.md`
- 用户提供的管理平台视觉参考图（深色、橙色强调、左侧导航、卡片化信息）

## 背景与定位

到第 25 节为止，内核能力齐了，但对外只有 CLI 一个出口。本节补能力五：把内部能力包装成 REST API，
让任何能发 HTTP 请求的业务系统把 Agent 接进自己的流程；同时做出**第一版只读管理平台**，
它没有独立后端，所有数据都来自同一批端点——这本身也是对"API 是否完备"的验收。

**边界声明**：

- **不做认证**（内网部署；API Key / JWT 属扩展阶段）、不做 SSE / WebSocket / 限流 / RBAC。
- 管理平台**只读**：不提供创建/编辑/删除 Agent、不提供写入记忆、不提供触发 Webhook。
  界面上不出现"假按钮"。
- 不做 `/api/v1/agents` 的增删改（29、30 节正题），不做 Profile 管理、Memory 写入、Prometheus。
- Web 层不承载业务逻辑：Controller 只做参数校验、委托、响应包装，处理一律走 `AgentService`。

## 与既有实现的冲突及收敛结论

仓库的 `oryxos-web` 已有 6 个 Controller 与统一异常处理器，但**多数是占位实现**（假 sessionId、
假 Profile 列表、回显消息），与本节的"真实只读 API"要求冲突。逐条结论如下：

| # | 冲突点 | 文档要求 | 仓库现状 | 本轮结论 |
|---|---|---|---|---|
| **C-1** | 端点口径 | 核心阶段 10 个端点 | 实际路由 10 条但集合不同（含 `/agent/chat`、`/providers`，缺 `/sessions` 列表、`/memory`、`/agents/{name}/invoke`） | 以文档语义为准重排，最终 **13 个端点**：会话 5（创建/列表/详情/发消息/归档）、Agent 2（`/agent/chat` 兼容 + `/agents/{name}/invoke`）、查询 4（profiles 列表+详情、memory、tools）、Provider 1、系统 2。多出的 3 个（会话列表、Profile 详情、Provider 列表）均服务于管理平台的只读页面，已在 `contracts/web-api.md` 显式登记 |
| **C-2** | 数据真实性 | "管理平台没有自己的后端，所有数据来自端点" | `Session`/`Profile`/`Provider`/`System` 控制器返回 `Map.of("sessionId", "session-" + now)`、两条硬编码 Profile 等桩数据 | **全部改为真实依赖**（`SessionManager`、`AgentService`、`ProfileRegistry`、`ProviderModelRegistry`、`MemoryService`、`SandboxProperties`）。管理平台若展示假数据，等于用界面掩盖"API 不通" |
| **C-3** | 兼容端点 `/agent/chat` | 未列出 | 已存在、已有 12 个用例覆盖、语义完好（按身份复用会话） | **保留**，不删不加逻辑；新端点 `/agents/{name}/invoke` 走**一次性会话**（无状态调用），两者语义不同、并存 |
| **C-4** | 500 是否泄漏内部信息 | 明确"内部异常细节进日志，对外只说内部错误" | `GlobalExceptionHandler` 兜底直接 `e.getMessage()`，会把 `jdbc:sqlite:...` 这类内幕吐给外部 | **修复**：500 统一 `"内部错误"` + ERROR 日志（含 `runId` 便于与审计对照）。同时把响应体从 `Map` 收敛为 `ErrorBody` record（结构化、可生成 OpenAPI schema） |
| **C-5** | 管理平台页面集合 | 5 页（会话/Profile/Tool/记忆/状态） | 无前端 | 采用参考图的 **8 页**：概览、会话、Agent、Provider、Tool、Sandbox 白名单、长期记忆、运行状态。其中"Sandbox 白名单"页**只展示开关状态与三类清单条数**，不展示路径/域名——当前没有认证，暴露白名单内容等于把安全边界画给所有人看 |
| **C-6** | `/memory` 的读路径 | "返回 MEMORY.md 全文" | `MemoryService` 只有 `buildContext/remember/recall` | 给门面增加**只读快照** `snapshot()`（宪法 V：记忆只经门面访问），实现委托 `LongTermMemory.load()`；页面标注"归档区按 4000 字符截断"，避免把注入口径误读成磁盘原文 |
| **C-7** | 前端形态 | 纯 HTML + 原生 JS，无构建工具，放 `static/admin/` | 无 | 纯静态三件套（`index.html` / `admin.css` / `admin.js`），**零外部 CDN**（离线可用）；`GET /admin` → 302 `/admin/index.html` |
| **C-8** | 冒烟测试类名 | `WebSmokeIT` | 父 POM 未配 failsafe，`*IT` 不会在 `mvn test` 中运行 | 命名 `WebSmokeTest`（surefire 默认包含 `*Test`），保证**每次构建都真的跑到**；为一个冒烟测试引入 failsafe 不划算 |
| **C-9** | 历史/详情口径 | "查历史" | `Session` 有完整消息列表但无对外查询 | `GET /sessions/{id}` 返回会话摘要 + **最近 100 条**消息；不额外开 `/messages` GET，避免同一份数据两个入口 |

## 现状与缺口

| 缺口 | 当前表现 |
|---|---|
| 端点是假的 | 建会话返回 `session-<毫秒>`，发消息回显"收到消息：xxx"，Profile 列表是两条硬编码 |
| 无状态调用缺失 | 业务系统最常用的一次性调用没有端点 |
| 记忆无读入口 | 管理平台"长期记忆"页拿不到数据 |
| 500 泄漏内幕 | 兜底处理器把异常 message 原样返回 |
| 无前端 | `static/admin/` 不存在，`/admin` 404 |
| 无冒烟测试 | 6 个 Controller 只覆盖 2 个；没有一次"真实上下文装得起来"的验收 |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 真实只读 API：端点上的数据必须是真的 (Priority: P1)

业务系统调 13 个端点拿到的是**当前实例的真实状态**：会话是真会话、Profile 来自注册表、
Tool 来自工具注册表、Provider 来自全局配置、记忆来自 `MEMORY.md`。

**Why this priority**: 假数据比没有接口更糟——它会让人基于错误信息做集成决策。

**Independent Test**: 不启模型、不连外网。用替身依赖 + standalone MockMvc 断言"Controller 是否
原样委托、返回是否来自依赖"；真实上下文由 `WebSmokeTest` 覆盖。

**Acceptance Scenarios**:

1. **Given** 一个已存在的会话，**When** `POST /sessions/{id}/messages`，**Then** `AgentService.process(session, content)` 恰好被调用一次，响应 `role=assistant`。
2. **Given** 不存在的会话，**When** 同上，**Then** 404 + `SESSION_NOT_FOUND`，`AgentService` 零调用。
3. **Given** 内容为 40KB 的消息，**When** 同上，**Then** 400 且**未进入** `AgentService`。
4. **Given** 注册表里有 3 个 Profile，**When** `GET /profiles`，**Then** 返回这 3 个（不是硬编码的两条）。
5. **Given** 全局配置了 1 个 Provider，**When** `GET /providers`，**Then** 返回该 Provider，且**不含 api-key**。
6. **Given** `MEMORY.md` 有内容，**When** `GET /memory`，**Then** 返回该内容与字符数。
7. **Given** 未知 Agent 名，**When** `POST /agents/{name}/invoke`，**Then** 404 + `PROFILE_NOT_FOUND`，不创建会话。

---

### User Story 2 - 错误只有一种形状，且 500 不泄漏内幕 (Priority: P1)

所有失败响应都是 `errorCode` / `message` / `timestamp` 三字段；状态码语义固定
（400/404/500/503/504）。500 永远只对外说"内部错误"，细节进日志。

**Why this priority**: 对接方只需要写一套错误解析；而 500 泄漏连接串这类信息是真实的安全缺陷（现有实现就泄漏）。

**Independent Test**: 逐类异常断言状态码与响应体；专门断言 500 的响应文本**不包含**内部异常里的关键串。

**Acceptance Scenarios**:

1. **Given** 内部抛出 `IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed")`，**When** 请求触发 500，**Then** `errorCode=INTERNAL_ERROR`、`message=内部错误`，且响应体不含 `jdbc:sqlite`。
2. **Given** 参数错误 / 会话不存在 / Provider 故障 / Agent 超时，**When** 分别触发，**Then** 分别 400 / 404 / 503 / 504，且都是三字段结构。
3. **Given** 任意错误响应，**When** 查看结构，**Then** 只含三字段（不夹带堆栈、不夹带内部类名）。

---

### User Story 3 - 只读管理平台：一眼看清这个实例在干什么 (Priority: P1)

打开 `http://localhost:8080/admin`，深色简洁界面，左侧 8 个导航，右侧数据；没有任何写操作入口；
出错时把统一错误 JSON 里的 `message` 显示出来，而不是白屏。

**Why this priority**: 这是本节的第二个交付物，也是"API 完备性"的可视化证据。

**Independent Test**: 静态资源可达 + `WebSmokeTest` 断言三个页面的数据端点均 200；其余靠人工按
`quickstart.md` 逐页核对（真数据、无写入口、无外链依赖）。

**Acceptance Scenarios**:

1. **Given** 应用已启动，**When** 访问 `/admin`，**Then** 302 到 `/admin/index.html` 并渲染概览页。
2. **Given** 概览页，**When** 加载完成，**Then** 显示 4 张统计卡（Agent 数、Tool 数、活跃会话数、Provider 数）、五大核心能力、技术栈。
3. **Given** 会话页，**When** 点击某一行，**Then** 展开该会话最近 100 条消息（只读）。
4. **Given** 任意页面，**When** 端点报错，**Then** 内容区显示 `errorCode + message`，导航仍可用。
5. **Given** 任意页面，**When** 查看界面，**Then** 不存在新建/编辑/删除/触发按钮。
6. **Given** 断网/无 CDN 环境，**When** 打开页面，**Then** 样式与脚本正常（零外部依赖）。

---

### User Story 4 - 真实上下文冒烟：装配没炸、静态资源没丢 (Priority: P2)

一次真实 Spring 上下文启动，验证 Controller 被扫描、Repository 被扫到（18 节"Found 0 repositories"的坑）、
静态资源可访问、关键端点可达。

**Why this priority**: 切片测试测不到 Bean 装配与资源打包，这类问题只会在 `serve` 时炸。

**Independent Test**: `WebSmokeTest`（`@SpringBootTest(RANDOM_PORT)`，替换为临时 SQLite 与测试 Provider，不调模型）。

**Acceptance Scenarios**:

1. **Given** 真实上下文，**When** `GET /api/v1/health`，**Then** 200 且 `status=UP`。
2. **Given** 真实上下文，**When** `GET /api/v1/tools`，**Then** 200 且包含内置工具（证明工具装配生效）。
3. **Given** 真实上下文，**When** `GET /admin/index.html`，**Then** 200 且 `Content-Type: text/html`。
4. **Given** 真实上下文，**When** 启动完成，**Then** 不出现 repository 扫描失败或 Bean 缺失。

---

### Edge Cases

- **消息内容为空白**：400，且不进入 `AgentService`（空消息只会换来一次无意义的模型调用）。
- **消息超 32KB**：400，提示字节数；限额按 **UTF-8 字节**而非字符数（中文一条 3 字节）。
- **会话已归档**：`POST /sessions/{id}/messages` 返回 400 + `SESSION_ARCHIVED`（资源存在但状态不允许写），避免往死会话里追加；报 404 会让人误以为会话丢了。
- **注册表为空**（没有 Profile / Tool）：返回空数组而非报错，也不返回假清单。
- **记忆文件不存在**：`GET /memory` 返回空内容（`chars=0`），不 500。
- **Provider 未配置**：`/providers` 返回空数组，`/info.canBuildAgents=false`（提示"当前无法调用模型"）。
- **静态资源直接访问 `/admin/`**：由静态资源处理器返回 `index.html`。

## Requirements *(mandatory)*

### Functional Requirements

**API 真实性与端点**

- **FR-001**: 六个 Controller MUST 只依赖真实组件（`AgentService`、`SessionManager`、`ProfileRegistry`、`ToolRegistry`、`ProviderModelRegistry`、`MemoryService`、`SandboxProperties`），MUST NOT 返回硬编码业务数据。
- **FR-002**: MUST 提供会话五端点：创建、列表、详情（含最近 100 条消息）、发消息、归档。
- **FR-003**: MUST 提供 `POST /api/v1/agents/{name}/invoke`：用一次性会话完成无状态调用，未知 Agent 返回 404。
- **FR-004**: MUST 提供 `GET /api/v1/memory`，经 `MemoryService` 门面读取，MUST NOT 直接读文件。
- **FR-005**: `GET /api/v1/info` MUST 含版本、Provider 列表与连通状态、沙箱策略摘要（开关 + 三类条数），MUST NOT 含 api-key 与白名单内容。
- **FR-006**: `GET /api/v1/providers` MUST NOT 返回 api-key。

**错误语义**

- **FR-007**: 所有错误响应 MUST 为 `ErrorBody(errorCode, message, timestamp)` 三字段结构。
- **FR-008**: 状态码 MUST 固定映射：参数错误 400、资源不存在 404、内部错误 500、Provider 故障 503、Agent 超时 504。
- **FR-009**: 500 响应 MUST NOT 包含内部异常消息、堆栈或类名；MUST 记录 ERROR 日志（含 `runId`）。

**输入约束**

- **FR-010**: 单条消息 MUST 限制在 32KB（UTF-8 字节）以内，超限 400 且不进入引擎。
- **FR-011**: 会话详情 MUST 最多返回最近 100 条消息；MUST 声明这一点而非静默截断。

**管理平台**

- **FR-012**: MUST 在 `static/admin/` 提供纯静态只读前端（HTML/CSS/JS），MUST NOT 依赖构建工具或外部 CDN。
- **FR-013**: `GET /admin` MUST 重定向到 `/admin/index.html`。
- **FR-014**: 前端 MUST 有 8 个页面（概览、会话、Agent、Provider、Tool、Sandbox 白名单、长期记忆、运行状态），所有数据来自上述端点。
- **FR-015**: 前端 MUST NOT 提供任何写操作控件（新建/编辑/删除/发送/触发）。
- **FR-016**: Sandbox 页 MUST 只展示开关与三类清单条数，MUST NOT 展示路径、命令、域名内容。
- **FR-017**: 端点失败时前端 MUST 展示统一错误体中的 `errorCode` 与 `message`，MUST NOT 空白或伪成功。

**装配与验证**

- **FR-018**: 系统 MUST 在真实上下文中装配全部 Controller（`WebSmokeTest` 验证）。
- **FR-019**: 冒烟测试 MUST 使用临时 SQLite 与测试 Provider，MUST NOT 依赖真实模型或外网。
- **FR-020**: 既有 `/api/v1/agent/chat` 与其测试 MUST 保持通过（兼容端点不回归）。

### Key Entities

- **会话视图（SessionSummary / SessionDetail）**：`sessionId`、`profileName`、`channel`、`userId`、`status`、`messageCount`、`createdAt`、`lastActiveAt`；详情追加 `messages[≤100]`。
- **调用请求/响应（InvokeRequest / InvokeResponse）**：`message` → `sessionId`、`agent`、`content`。
- **错误体（ErrorBody）**：`errorCode`、`message`、`timestamp`。
- **系统信息（InfoResponse）**：`name`、`version`、`description`、`providers[]`、`sandbox{enabled, allowedPaths, allowedCommands, allowedDomains}`、`canBuildAgents`。
- **管理平台页面**：概览 / 会话 / Agent / Provider / Tool / Sandbox 白名单 / 长期记忆 / 运行状态（全部只读）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 13 个端点在真实上下文中全部可达（`WebSmokeTest` 覆盖 health/info/profiles/tools/admin）。
- **SC-002**: Controller 中硬编码业务数据条数为 **0**（`Session` / `Profile` / `Provider` / `System` 四处桩全部移除）。
- **SC-003**: 500 响应泄漏内部信息次数为 **0**（含连接串、类名、堆栈）。
- **SC-004**: 管理平台 8 个页面均能从端点取到数据并渲染，写操作控件数为 **0**。
- **SC-005**: 前端外部依赖数（CDN / npm / 构建工具）为 **0**。
- **SC-006**: 超 32KB 消息、空白消息、未知会话、未知 Agent 四类非法输入均被挡在引擎之前（引擎调用次数 0）。
- **SC-007**: 全仓 `mvn test` **BUILD SUCCESS**，失败数 **0**。

## Assumptions

- **A-001（端点口径以文档语义为准）**：10 → 13 的差异已在 C-1 记录；多出的三个端点都是"查"能力，不改变"Controller 薄"的约束。
- **A-002（管理平台 8 页而非 5 页）**：以用户提供的视觉参考为准（见 C-5）；信息密度提升但仍是只读。
- **A-003（Sandbox 页只给条数）**：无认证阶段不暴露安全边界内容（见 FR-016）；等认证落地后再评估展示全文。
- **A-004（记忆展示注入口径）**：`snapshot()` 复用 `load()` 语义（归档区 4000 字符截断），页面显式标注，避免被当作磁盘原文。
- **A-005（一次性会话的渠道）**：`/agents/{name}/invoke` 使用 `channel=invoke`，与 `web` / `cli` / `scheduler` 区分，便于审计归类。
- **A-006（不做认证）**：与文档一致，假设内网部署；`/admin` 与 API 同级暴露。
- **A-007（冒烟测试用测试 Provider）**：以 `oryxos.providers[0]` 覆盖为测试值，仅验证装配，不发真实请求。
