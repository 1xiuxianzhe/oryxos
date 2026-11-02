# Phase 1 Data Model: ReAct 循环

**Feature**: `002-react-loop` | **Date**: 2026-09-16
**来源**: [spec.md](./spec.md) 的 Key Entities 与 Functional Requirements；技术决策见 [research.md](./research.md)

---

## 1. 实体总览

| 实体 | 归属层 | 持久化 | 说明 |
|------|--------|--------|------|
| Session（会话） | core | SQLite `sessions` | 一次对话的上下文容器 |
| Message（消息） | core | 随 Session（`messages_json`） | 会话中的一条往来记录 |
| Profile（Agent 配置） | core | 文件系统（Profile YAML） | 定义一个 Agent 的行为 |
| LlmRequest | core | 否 | 一次模型调用的输入值对象 |
| LlmResponse / ToolCall | core | 否 | 模型返回的文本与工具请求 |
| ToolResult | core | 否 | 一次工具执行的结果 |
| ToolInvocation（工具调用审计） | core 接口 / storage 实体 | SQLite `tool_invocations` | 工具调用落库记录（含所属请求 `run_id`） |
| AgentRun（请求级审计） | core 接口 / storage 实体 | SQLite `agent_runs` | 一次处理的落库记录，见 §10（FR-022） |
| ReActResult | core | 否 | 一次处理的结局元数据：停止原因 / 轮数 / 工具次数 / token 汇总 / 耗时 |
| AgentContext（当前 Agent 上下文） | core | 否（线程作用域） | 处理期间可被工具读取的当前 Profile 与本次 `runId` |
| StopReason | core | 进日志 + 落 `agent_runs.stop_reason` | 本次处理的停止原因 |

---

## 2. Session（会话）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| sessionId | String | 必填、唯一（主键） | 会话标识；审计与工具调用均以其关联 |
| profileName | String | 必填 | 所属 Agent 配置名 |
| channel | String | 必填 | 来源渠道（核心阶段为 cli） |
| userId | String | 可空 | 用户标识 |
| messages | List&lt;Message&gt; | 按发生顺序、只追加 | 会话历史 |
| status | String | `active` / `archived` | 会话状态 |
| createdAt / lastActiveAt / archivedAt | Instant | `createdAt` 创建时写入 | 时间戳 |

**Validation / Rules**
- `messages` 只允许追加，不允许就地修改历史（`addMessage` 同时更新 `lastActiveAt`）。
- 状态机：`active → archived`（单向，归档时写 `archivedAt`）。
- 持久化映射：元数据 + `messages_json`（Jackson 序列化 `messages`）；`get` 时先查内存、未命中再查库（FR-009 / SC-004）。

---

## 3. Message（消息）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| role | String | `user` / `assistant` / `tool` | 消息角色 |
| content | String | 可空 | 文本内容 |

**Rules**
- **往返边界**：以 `role=user` 为一段"完整往返"的起点（见 D-04）。
- 工具结果以 `role=tool` 追加，内容包含工具输出或失败原因（FR-006、US3 场景 2）。
- `role=assistant` 也要累积（包含模型请求工具的那一轮），以保证事后可审计（坑三）。

---

## 4. Profile（Agent 配置）

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| name | String | — | Profile 名 |
| description | String | 可空 | 描述 |
| identity | String | 可空 | 角色设定（system prompt 第一段） |
| provider | ProviderConfig | 必填 | `name` / `model` / `temperature` |
| tools | List&lt;ToolConfig&gt; | 可空 | 可用工具 |
| skills | List&lt;SkillConfig&gt; | 可空 | 技能；`name` → `.oryxos/skills/<name>/SKILL.md` |
| mcpServers / channels / notifyChannels / schedules | List | 可空 | 其它能力使用（本特性不消费） |
| bootstrap | BootstrapConfig | 可空 | `files`（显式引用的 Bootstrap 文件列表）、`prompt`（内联片段） |
| settings | Map&lt;String, Object&gt; | 可空 | 运行参数，见下表 |

**settings 约定（本特性新增消费）**

| 键 | 类型 | 默认 | 用途 |
|----|------|------|------|
| `max_iterations` | Integer | 10 | 循环最大轮数（FR-004） |
| `max_history_turns` | Integer | 20 | 历史保留的完整往返数（FR-012） |
| `processing_timeout_seconds` | Integer | 60 | 单次处理整体耗时上限（FR-019） |

**Validation / Rules**
- 便捷访问器对缺失/非法值回退到默认值，非法值记 WARN（不因一个坏配置让 Agent 不可用）。
- `provider.name` 必须已在全局层声明（由能力一的 ProfileLoader 在启动时校验）。
- `bootstrap.files` 与 `skills[].name` 为**显式引用**：缺失 → 组装输入时报错（FR-015、D-02）。
- 未配置 `bootstrap.files` → 按默认三件套 `AGENTS.md`、`SOUL.md`、`USER.md` 尝试加载，缺失仅 WARN。

---

## 5. LlmRequest / LlmResponse / ToolCall

**LlmRequest**

| 字段 | 类型 | 说明 |
|------|------|------|
| systemPrompt | String | 角色与启动设定 + 当前日期时间（第一部分） |
| memory | String | 长期记忆片段（若无则空，不产生消息） |
| history | List&lt;Message&gt; | 最近 N 次完整往返（已截断） |
| tools | List&lt;ToolDescription&gt; | 可用工具 schema（name / description / inputSchema） |

**LlmResponse**

| 字段 | 类型 | 说明 |
|------|------|------|
| text | String | 文本内容（可空） |
| toolCalls | List&lt;ToolCall&gt; | 模型请求的工具调用（可空） |
| usage | TokenUsage | token 用量（可空） |

**ToolCall**：`name`（工具名）、`arguments`（参数，JSON 字符串）。

**ReActResult**（循环的返回值：答复 + 过程元数据）

| 字段 | 类型 | 说明 |
|------|------|------|
| text | String | 返回给用户的文本（失败收尾时是明确提示，不是伪装的正常答复） |
| stopReason | StopReason | 停止原因 |
| iterations | int | 已完成的循环轮数（等于 LLM 调用次数） |
| toolCallCount | int | 本次处理执行的工具调用总数 |
| totalUsage | TokenUsage | 累计 token 用量（provider 未返回用量时按 0） |
| elapsedMs | long | 本次处理耗时 |

**Rules**
- 停止判定只看 `toolCalls` 是否为空，与 `text` 是否为空无关（D-05、FR-003）。
- `text` 与 `toolCalls` 同为空 → 视为无效响应，以明确提示收尾（FR-016）。
- `MAX_ITERATIONS` / `EMPTY_RESPONSE` 是**以正常返回值结束**的，只返回 `String` 无法与"模型真答复"区分；请求级审计需要这些元数据，故循环一并返回（FR-022）。
- 超时以 `ProcessingTimeoutException` 结束，异常本身携带**已完成轮数 / 工具次数 / 已消耗 token**，使失败场景也能记录真实进度。

---

## 6. ToolResult（工具结果）

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 成功标记 |
| content | String | 成功时的结果内容 |
| error | String | 失败时的错误原因 |
| retryable | boolean | 是否可重试 |

**Rules**
- 工具内部异常必须转换为 `success=false` 的结果（携带原因），**不向上抛出**，以便循环继续（US3 场景 2）。
- 工具结果（成功或失败）都要回填进会话，供下一轮模型看到。

---

## 7. ToolInvocation（工具调用审计）

| 字段 | 列名 | 类型 | 说明 |
|------|------|------|------|
| invocationId | invocation_id | TEXT PK | 调用标识（UUID） |
| sessionId | session_id | TEXT | 关联会话（FR-017） |
| runId | run_id | TEXT | 所属请求级审计 `agent_runs.run_id`（FR-023）；不在处理上下文中调用时为 null |
| profileName | profile_name | TEXT | 当前 Agent 配置名 |
| toolName | tool_name | TEXT | 工具名 |
| parameters | parameters | TEXT | 入参（JSON） |
| status | status | TEXT | `SUCCESS` / `FAILED` |
| success | success | INTEGER | 成功标记（新增） |
| errorMessage | error_message | TEXT | 失败原因（由 `error` 更名） |
| result | result | TEXT | 结果内容 |
| latencyMs | latency_ms | INTEGER | 耗时（新增） |
| startedAt | started_at | INTEGER | 开始时间（epoch 毫秒） |
| completedAt | completed_at | INTEGER | 结束时间（epoch 毫秒） |

**Validation / Rules**
- 成功与失败**都要**写一条（FR-008、SC-003）。
- 审计写入失败不得阻断主流程：记 ERROR 日志后继续（FR-020、SC-009）。
- 表结构由手工脚本维护；时间列使用整数亲和性（D-07）。

---

## 8. AgentContext（当前 Agent 上下文）

| 成员 | 说明 |
|------|------|
| `set(Profile)` / `set(Profile, runId)` | 处理入口设置当前 Profile（及本次处理的 runId） |
| `current()` | 工具/组件读取当前 Profile；未设置时返回 null |
| `currentRunId()` | 存储层审计读取当前 runId，把明细挂到 `agent_runs`；未设置时返回 null |
| `clear()` | 处理出口清理 Profile 与 runId（必须在 `finally` 中调用） |

**Rules**
- 生命周期与**一次处理**严格对齐：处理开始 set、结束（含异常）必须 clear（FR-010）。
- 作用域为线程（虚拟线程下每请求独立线程）。
- runId 同时由 `AgentService` 经 **MDC**（key = `runId`）注入日志上下文，使一次处理的全部日志（含 `ReActLoop` / `ToolExecutor`）都能与 `agent_runs` 的那条记录对照（FR-021 / SC-010）。

---

## 9. StopReason（停止原因）

| 取值 | 触发条件 | 用户可见结果 |
|------|----------|--------------|
| `FINAL_ANSWER` | 模型未请求工具且文本非空 | 模型答复 |
| `EMPTY_RESPONSE` | 模型既无文本也无工具请求 | 明确提示（FR-016） |
| `MAX_ITERATIONS` | 达到最大轮数 | "已达到最大轮数（N），已停止"（FR-005） |
| `TIMEOUT` | 超过整体耗时上限 | 抛 `ProcessingTimeoutException`，由入口展示（FR-019） |
| `MODEL_ERROR` | 模型调用失败（网络/限流/鉴权/错误响应） | 抛 `ModelCallException`，由入口展示（FR-018） |
| `AGENT_CONFIG_ERROR` | 请求的 Profile 不存在 | 抛 `AgentConfigurationException`，处理尚未开始 |
| `INTERNAL_ERROR` | 其他未预期异常 | 异常上抛，由入口展示（FR-018） |

**Rules**
- `FINAL_ANSWER` / `EMPTY_RESPONSE` / `MAX_ITERATIONS` 以**正常返回值**结束；其余四种以异常结束。
- 每个终态都要落地两处：一条带 StopReason 的处理级日志 + 一条 `agent_runs` 终态记录（FR-021 / FR-022、SC-010 / SC-011）。
- `success` 仅 `FINAL_ANSWER` 为 true（`EMPTY_RESPONSE` 虽有文本返回，仍视为失败）。

---

## 10. AgentRun（请求级审计）

一次处理一条记录，**两阶段写**：处理开始落 `RUNNING`（此时"问的是什么、用哪个模型"已确定并写入），处理结束更新为 `FINISHED`。

| 字段 | 列名 | 类型 | 说明 |
|------|------|------|------|
| runId | run_id | TEXT PK | 本次处理的唯一 ID；同时写入 `llm_calls` / `tool_invocations` 用于关联 |
| sessionId | session_id | TEXT | 所属会话 |
| profileName | profile_name | TEXT | Agent 配置名 |
| channel / userId | channel / user_id | TEXT | 来源渠道与用户标识 |
| userMessage | user_message | TEXT | 用户原始输入——后台展示"问的是什么" |
| provider / model | provider / model | TEXT | 本次使用的 provider 与模型；配置解析失败时为 null |
| status | status | TEXT | `RUNNING` / `FINISHED` |
| stopReason | stop_reason | TEXT | `StopReason` 枚举名 |
| success | success | INTEGER | 仅 `FINAL_ANSWER` 为 1 |
| iterations | iterations | INTEGER | 已完成的循环轮数 |
| toolCallCount | tool_call_count | INTEGER | 执行的工具调用总数 |
| promptTokens / completionTokens / totalTokens | prompt_tokens / completion_tokens / total_tokens | INTEGER | 本次处理累计 token 用量 |
| errorMessage | error_message | TEXT | 失败原因（成功时为 null） |
| startedAt / completedAt | started_at / completed_at | INTEGER | 起止时间（epoch 毫秒） |

**Validation / Rules**
- **成功与失败都必须落到终态**；处理中途被 kill 时，留下的 `RUNNING` 记录即为可追溯的异常终局（FR-022）。
- `recordFinished` 若查不到对应的 `RUNNING` 记录（started 写失败或被跳过），**必须补写一条** `FINISHED`——宁可字段不全，也不能让这次处理在库里消失。
- 写入失败只记 ERROR 日志、不抛出（FR-020 / SC-009）。
- 与明细的关系：`llm_calls.run_id` / `tool_invocations.run_id` 指向本表，后台可从一次请求下钻到它的每次模型调用与工具调用（FR-023）。
- 日志关联：`runId` 同时经 MDC 注入日志（见 §8），日志与库记录用同一个 ID 对照。

---

## 11. 与存储表的映射

| 实体 | 表 | 关键列 | 交付方式 |
|------|----|--------|----------|
| Session | `sessions` | session_id / profile_name / channel / user_id / messages_json / status / created_at / last_active_at / archived_at | `db/schema.sql` 手工建表（本特性追加） |
| ToolInvocation | `tool_invocations` | 见 §7（本特性新增 `run_id`） | `db/schema.sql` 手工建表 + 迁移脚本 |
| AgentRun | `agent_runs` | 见 §10（本特性新增表） | `db/schema.sql` 手工建表 + 迁移脚本 |
| LlmCall（能力一） | `llm_calls` | 本特性新增 `run_id` 关联请求 | 表已交付；列演进见 `db/migration/2026-09-18-agent-runs-and-run-id.sql` |

**Rules**
- 新建表一律进 `db/schema.sql`（`CREATE TABLE IF NOT EXISTS`）；索引只允许建立在**建表时即存在**的列上（避免旧库缺列导致启动失败，能力一已踩坑）。
- 既有库的列演进写入 `db/migration/`，并在脚本内注明适用场景。
- 为**既有表**新增的列（如 `llm_calls.run_id`）不放进 `db/schema.sql` 的索引——老库缺列会让 `CREATE INDEX` 直接失败；索引写在迁移脚本里，开发库另可由 `ddl-auto=update` 自动补列。
