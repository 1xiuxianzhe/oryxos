# Feature Specification: 动态管理 Agent —— 上传即上线、一句话生成

**Feature Branch**: `class-30`

**Created**: 2026-10-30

**Status**: Draft（口径已确认，待实现）

**Input**: `docs/class/第30节：动态管理 Agent 一句话生成、上传即上线.md`

**基座声明**：**以本仓库现有代码为基座**。文档（尤其 §5「实现回写」）描述的是另一条代码线的产物，
其中 `ApiResponse`、`agentStore`、`MemoryServiceImpl`、`ProviderService.complete/chat`、
`OryxTool.execute(JsonNode)`、`ToolExecutionContext`、`scheduledTasks` 等在本仓**均不存在**。
本节只借用它的**能力清单**，实现形态一按本仓（裸 DTO + `ErrorBody`、`LlmClient.call`、
`AgentContext`、`AgentLoader`/`ProfileRegistry`/`AgentScheduler`、`MemoryLocator`）。

---

## 背景与定位

29 节立住了"一个目录 = 一个 Agent"，但入口还停在"登录服务器放目录 + 重启"。本节把最后一段补上：

1. **改目录即改运行时**：`WorkspaceWatcher` 监听 `.oryxos/agents/`，目录新增/修改/删除后几秒内生效，**免重启**；
2. **API 与手工殊途同归**：两条录入路径最终都调同一段 `register(agentDir)`；
3. **管理台从"能看"到"能管"**：Agent 增删改查、按描述生成文件、工作区文件浏览/编辑、Agent 专属会话与记忆；
4. **前端用 React 重做**（用户选择，作为学习载体），与既有原生管理台并存。

---

## 与文档的关键差异（本 spec 的强制口径）

| # | 文档说法 | 本仓现状 | 收敛结论 |
|---|---|---|---|
| **D-1** | `ApiResponse` 统一信封、`agentStore`、`MemoryServiceImpl`、`ToolExecutionContext` | 均不存在；有 `ErrorBody`、`AgentContext`、`DefaultMemoryService` | 一按本仓；文档只作能力清单 |
| **D-2** | 记忆落 `<agentDir>/MEMORY.md` | 29 节已实现 `<agentDir>/memory/MEMORY.md` | **保持 `memory/MEMORY.md`**，不做迁移 |
| **D-3** | 移除全局 `GET /api/v1/memory` | 该端点存在，管理台在用 | **保留**，另加 `GET /api/v1/agents/{name}/memory` |
| **D-4** | `/api/v1/agents` 承担全部读写 | 已有 `/api/v1/profiles` 列表/详情 | **写走 `/agents`**，列表/详情仍以 `/profiles` 为准；`GET /agents` 返回同源数据以方便前端 |
| **D-5** | `providerService.complete/chat` | 只有 `LlmClient.call(sessionId, profile, request)` | 生成走 `LlmClient.call`，自建 author Profile |
| **D-6** | 生成用"系统默认 model" | 本仓 model 只在各 Profile 里 | 新增 `oryxos.author.provider` / `oryxos.author.model`；**model 缺失 → 503 可读原因**，绝不发 `model=null`。这两个键**一身两职**：生成用的模型，同时也是新建 Agent 模板里预填的 provider/model（少一套配置就少一个忘记配的地方） |
| **D-7** | create 只写 `AGENT.md`（§2）或脚手架整目录（§5.3） | 无 | **脚手架整目录**：`AGENT.md` + `scripts/` + `skills/` + `REFERENCE.md`（模板内容，不含可执行脚本） |
| **D-8** | `WorkspaceWatcher` 启动时"先全量扫一遍" | 启动扫描已由 `AgentLoader` 负责 | 监听器**不重复全量扫**（避免双份注册），只负责增量事件 |
| **D-9** | 删除 = 注销 → 移出索引 → 归档 | — | 顺序固定；归档到 `.oryxos/archive/<name>-<yyyyMMdd-HHmmss>/` |
| **D-10** | 文档未提重复注册 | `AgentScheduler.registerProfile` 对同 key 是 `handles.put`，**旧 future 不会被取消** | `register(agentDir)` 必须**幂等**：先 `unregisterProfile(name)` 再注册 |

---

## User Scenarios & Testing

### US1 · API 建 Agent 即上线（P1）

`POST /api/v1/agents {name, description}` → 脚手架出完整目录 → 派生注册 → **不重启**即可见、到 cron 自己跑；
中途失败必须回滚，系统里不留半个 Agent。

**验收**
1. 合法请求：返回 200/201，`.oryxos/agents/<name>/` 出现 4 项，`GET /api/v1/profiles` 立刻可见。
2. 名字非法（大写/下划线/中文/超长）→ 400，**一个目录都不写**。
3. 名字已存在（注册表或目录）→ 400，不覆盖、不写盘。
4. 注册失败（如 provider 未声明）→ 抛错且**已写的目录被删回**，`registry.exists(name)==false`。
5. 带 `schedules` 的模板 → 定时立即注册（`registeredTasksOf(name)` 非空）。

### US2 · 丢目录即上线（P1）

不经 API，直接往 `.oryxos/agents/` 拷一个目录 / 改 `AGENT.md` / 删目录 → 几秒内注册、更新、注销，全程不重启。

**验收**
1. 新增目录 → 被注册，`GET /api/v1/profiles` 可见。
2. 修改 `AGENT.md` 的 `schedules` → 旧句柄被取消、新句柄生效（**不出现两条 cron 一起跑**）。
3. 删除目录 → 定时取消 + 移出注册表。
4. 半写/非法内容 → **保留旧注册**，只记录跳过原因（不得让 Agent 消失）。
5. 监听器单线程后台运行，事件处理异常不外抛、不拖垮后续事件。

### US3 · 一句话/描述生成文件（P2）

`POST /api/v1/agents/{name}/generate-files {description}` → 一次 LLM 调用 → 返回 `{相对路径: 内容}`，
**不落盘、不注册**；用户改完再 `POST /api/v1/agents/{name}/files` 保存并生效。

**验收**
1. 生成结果能被 `AgentLoader` 解析（否则 400 + 可读原因），并剥掉模型多吐的 ``` 围栏。
2. 未配置 `oryxos.author.model` → 503，消息说明该配哪个键。
3. 生成调用落 `llm_calls` 审计（sessionId 固定为 `author`，runId 为空可接受）。
4. 保存一组文件：先校验 `AGENT.md` 可解析，非法则**一个字节都不写**；合法则写入并重注册。

### US4 · 删除与更新语义（P1）

- **DELETE**：注销定时 → 移出索引 → 整目录归档（不物理删）。
- **PUT**：覆写 `AGENT.md` → 重注册（`schedules` 变则先注销旧的）；新内容非法 → 恢复旧内容并报错。

**验收**
1. 删除顺序可断言（`InOrder`：scheduler → registry → store.archive）。
2. 删除后 `.oryxos/archive/<name>-<时间戳>/AGENT.md` 存在，审计数据仍可查。
3. 更新失败后磁盘内容与更新前**逐字相同**，注册表仍是旧 Profile。

### US5 · 工作区文件浏览与编辑（P2）

`GET /api/v1/workspace/tree`、`GET /api/v1/workspace/file?path=`、`POST /api/v1/workspace/file`。

**验收**
1. tree 返回 `agents/`（每个 Agent 可展开）与 `archive/`，深度/条目数有上限。
2. `path=../../etc/passwd`、绝对路径、符号链接逃逸 → **400**。
3. 读取非文本（二进制/超大/非 UTF-8）→ 400 + 可读原因。
4. 写 `agents/<name>/AGENT.md` **走 update 语义**（校验 + 重注册）；写其它文件直接落盘。

### US6 · 管理台（React 整体改造）（P2）

`/admin/` 整体由 React 单页应用托管（原手写的 index.html / admin.js 已下线）：
侧边栏分组为「概览 / Agent 列表 / 定时任务」+「OS 运行时」下的会话、Provider、Tool、SandBox、长期记忆、工作区，
Agent 详情提供 5 个 tab（基本信息 / 生成 / 文件 / 会话 / 记忆）。

**验收**
1. 页面能从零建一个 Agent，并在列表里看到它。
2. 详情页能生成草稿、编辑、保存并生效（`schedules` 变更即时反映）。
3. 文件树能展开 Agent 目录、查看 `AGENT.md`/`scripts/` 内容并可保存。
4. 会话 tab 能发消息、看到历史；记忆 tab 只看只读快照。

### Edge Cases

- 目录名与 `archive/` 下已有归档同名 → 归档名带时间戳，永不覆盖。
- Agent 目录被外部删除后又同名创建 → 视为新 Agent，正常注册。
- `POST /agents` 与监听器同时生效 → 幂等注册，不产生双份定时。
- `AGENT.md` 被编辑器原子保存（临时文件 + rename）→ 去抖后只处理一次，且不被临时文件干扰。
- 生成内容含 `skills:` 字段 → 与 29 节一致（硬错），返回 400 并说明原因。
- 工作区路径是文件而非目录 → tree 正常返回空列表，不报 500。
- 删除正在运行的 Agent → 取消后续触发，本次运行不强制中断。

---

## Requirements

**A. 目录存储（AgentStore）**

- **FR-001**: 提供 `create(name, description)`：建 `agents/<name>/`，写 `AGENT.md`（模板）+ 空 `scripts/`、`skills/` + `REFERENCE.md`（模板）。
- **FR-002**: 写 `AGENT.md` MUST 原子替换（临时文件 + move），避免监听器读到半写内容。
- **FR-003**: 提供 `archive(name)`：移到 `archive/<name>-<yyyyMMdd-HHmmss>/`；同名已存在时追加序号。
- **FR-004**: 提供 `delete(dir)`：仅用于创建失败的**回滚**（递归删）。
- **FR-005**: 名字校验复用 29 节规则（`^[a-z0-9][a-z0-9-]*$`），非法即 400。

**B. 生命周期（AgentLifecycleService）**

- **FR-006**: `register(agentDir)` MUST 幂等：先 `AgentScheduler.unregisterProfile(name)`，再 `ProfileRegistry.register`，再 `registerProfile`。
- **FR-007**: `create` MUST 先查重（注册表 + 目录）再写盘；注册失败 MUST 删掉已写目录。
- **FR-008**: `update(name, markdown)` MUST 先备份旧内容 → 原子写 → 解析校验 → 重注册；任一步失败 MUST 恢复旧内容。
- **FR-009**: `delete(name)` MUST 按"注销定时 → 移出注册表 → 归档目录"顺序，且不物理删除。
- **FR-010**: `unregister(name)`（手工删目录路径）MUST 幂等：不存在时只记日志。
- **FR-011**: `generate(name, description)` MUST 用 `LlmClient.call`（sessionId=`author`）且不落盘、不注册；结果 MUST 可被 `AgentLoader` 解析，否则 400。
- **FR-012**: `saveFiles(name, files)` MUST 先整体校验 `AGENT.md` 再写；写后重注册。

**C. 监听（WorkspaceWatcher）**

- **FR-013**: MUST 监听 `agents/` 与**每个** Agent 子目录（`WatchService` 不递归）。
- **FR-014**: MUST 去抖（静默 300ms 后处理），丢弃非 `AGENT.md` 变更引起的重复事件（脚本/资源变更不需要重注册）。
- **FR-015**: 目录存在且有 `AGENT.md` → `register`；目录消失 → `unregister`。
- **FR-016**: 处理异常 MUST 只记日志 + skipped 原因，MUST NOT 影响已注册的旧版本。
- **FR-017**: 单守护线程；启动时**不做**全量扫（扫描归启动装配）。

**D. Web 端点**

- **FR-018**: `POST /api/v1/agents`、`PUT /api/v1/agents/{name}`、`DELETE /api/v1/agents/{name}`、`GET /api/v1/agents`、`GET /api/v1/agents/{name}`。
- **FR-019**: `POST /api/v1/agents/{name}/generate-files`、`POST /api/v1/agents/{name}/files`。
- **FR-020**: `GET /api/v1/agents/{name}/memory`（只读快照）。
- **FR-021**: `GET /api/v1/agents/{name}/session`、`POST /api/v1/agents/{name}/session/messages`（固定 `admin/console/<name>`）。
- **FR-022**: `GET /api/v1/workspace/tree`、`GET /api/v1/workspace/file`、`POST /api/v1/workspace/file`。
- **FR-023**: 所有路径参数 MUST 做目录穿越防护（解析真实路径后必须落在 `.oryxos/` 内），越界 400。
- **FR-024**: 错误码沿用既有映射：参数/冲突 400，不存在 404，模型不可用 503。

**E. 前端（React）**

- **FR-025**: 管理台**整体**改为 React（Vite）实现：构建产物覆盖 `oryxos-web/src/main/resources/static/admin/`，
  对外只有 `/admin/` 一个入口，不保留并行的第二套前端（旧原生页面删除）。
- **FR-026**: 页面：Agent 列表（含新建）、Agent 详情（基本信息 / 生成 / 文件 / 会话 / 记忆）、工作区（树 + 只读/可编辑文件）。
- **FR-027**: 开发态通过 Vite proxy 指向 `localhost:8080`，无需 CORS 配置。

---

## Success Criteria

- **SC-001**: `POST /agents` 后不重启，列表可见、cron 到点真跑（真链路，人工验收）。
- **SC-002**: 手工丢目录 5 秒内可见（自动测试用 `handleChange` 直接驱动，不依赖 OS 时序）。
- **SC-003**: 同一 Agent 修改 `schedules` 后，触发次数 = 1 次/周期（幂等注册，无双份）。
- **SC-004**: 创建失败/更新失败后，磁盘与注册表均无残留（回滚测试断言）。
- **SC-005**: `file?path=../../x` 等 4 种越界形态全部 400。
- **SC-006**: 生成端点未配置 model 时返回 503 且消息含配置键名。
- **SC-007**: 前端能完成"新建 → 详情 → 生成 → 保存 → 会话 → 删除"整条链路。
- **SC-008**: 全仓 `mvn test` 全绿（既有 400+ 用例零回归）。

---

## Assumptions（用户已确认）

| # | 议题 | 定稿 |
|---|---|---|
| A-001 | 基座 | 以本仓代码为准，命名/形态不必与文档一致 |
| A-002 | 记忆路径 | 保持 `<agentDir>/memory/MEMORY.md` |
| A-003 | 全局 `/api/v1/memory` | 保留 |
| A-004 | 端点分工 | 写走 `/agents`，列表/详情仍以 `/profiles` 为准 |
| A-005 | 固定会话 | 做（`admin` / `console` / `<name>`） |
| A-006 | create 形态 | 脚手架整目录（AGENT.md + scripts/ + skills/ + REFERENCE.md） |
| A-007 | 文件编辑 | 允许改 `scripts/`，UI 标注"改脚本 = 改行为" |
| A-008 | 前端 | React（Vite）整体改造原管理台，`/admin/` 单入口（不并存第二套前端） |

---

## 本轮不做

认证/鉴权（内网假设）、zip/multipart 上传、Agent 启用停用状态、dry-run 试跑、版本历史、多轮追问式生成、在线协作编辑、跨平台文件监听兼容层。

---

## 实现回写（交付后校对，与本文的差异处以此节为准）

| # | 本文说法 | 实际实现 | 原因 |
|---|---|---|---|
| I-1 | `GET /api/v1/agents` 返回数组 | 返回 `{agents: [...], skipped: [...]}` | 坏目录"没生效"的原因必须能透到页面上，单靠数组表达不了 |
| I-2 | `GET /workspace/file` 文件不存在 → 404 | 统一 400 | 该端点的入参就是路径参数，路径错属参数错；404 留给"Agent 不存在" |
| I-3 | `MemoryService` 只需 `snapshot()` | 新增 `snapshot(Path agentDir)` | 按 Agent 查记忆必须经门面；让 Web 层自己拼 `memory/MEMORY.md` 会把实现细节变成协议 |
| I-4 | 未提 `AgentDefinitionException` | 新增（core/profile） | 解析失败要能被 400 原样展示；与内部的 `FrontmatterException` 分工明确 |
| I-5 | 未提生成结果的失败分级 | `AgentGenerationException.unavailable` 区分 503/400 | 配置缺失与"模型吐的不能用"对用户是两种动作（去配模型 / 改描述） |
| I-6 | 前端"重做"的范围 | 前端工程 `admin-web/`（Vite + React 18），产物覆盖 `oryxos-web/src/main/resources/static/admin/`；旧的原生 `index.html / admin.js / admin.css` 已删除 | 是**整体改造**而非新增并行应用：只有 `/admin/` 一个入口，避免两套前端长期分叉 |
| I-7 | 未提监听器启动时机 | 随 Bean 初始化启动（`afterPropertiesSet`），启动时只补注册监听键 | 依赖链保证 AgentLoader 已扫描完，避免双份注册 |
| I-8 | 未提信息架构 | 侧边栏按 31 节重分组：顶层「概览 / Agent 列表 / 定时任务」，「OS 运行时」收纳会话、Provider、Tool、SandBox、长期记忆、工作区；视觉沿用原管理台的深色 + 橙色 | 布局参照给定 `App.vue`；只借布局不换视觉，避免"改架构顺手换皮" |
