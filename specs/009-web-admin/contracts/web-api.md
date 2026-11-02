# Contract: OryxOS Web API v1

**Feature**: `009-web-admin` | **Version**: 1.0 | **Date**: 2026-10-21

统一前缀 `/api/v1`。请求/响应 `Content-Type: application/json`（静态页与文档页除外）。
所有错误响应结构见 `data-model.md` §4。

## 1. 端点清单（14 个）

| # | 方法 | 路径 | 用途 | 成功码 |
|---|---|---|---|---|
| 1 | POST | `/api/v1/sessions` | 创建会话 | 200 |
| 2 | GET | `/api/v1/sessions` | 会话列表 | 200 |
| 3 | GET | `/api/v1/sessions/{id}` | 会话详情 + 最近 100 条消息 | 200 |
| 4 | POST | `/api/v1/sessions/{id}/messages` | 发消息（同步返回回复） | 200 |
| 5 | DELETE | `/api/v1/sessions/{id}` | 归档会话 | 200 |
| 6 | GET | `/api/v1/sessions/{id}/runs` | 会话运行明细（提问 / 工具 / token） | 200 |
| 7 | POST | `/api/v1/agents/{name}/invoke` | 无状态调用 | 200 |
| 8 | POST | `/api/v1/agent/chat` | 兼容端点（按身份复用会话） | 200 |
| 9 | GET | `/api/v1/profiles` | Profile 列表 | 200 |
| 10 | GET | `/api/v1/profiles/{name}` | Profile 详情 | 200 |
| 11 | GET | `/api/v1/memory` | 长期记忆快照 | 200 |
| 12 | GET | `/api/v1/tools` | 工具清单 | 200 |
| 13 | GET | `/api/v1/providers` | Provider 清单 | 200 |
| 14 | GET | `/api/v1/health`、`/api/v1/info` | 健康 / 系统信息 | 200 |

> 文档口径为 10 个（会话 4 + Agent 1 + 查询 3 + 状态 2）。本轮多出：会话列表（#2）、
> 会话运行明细（#6）、Profile 详情（#10）、Provider 清单（#13）——四者分别服务于管理平台的
> 会话页（列表 + 详情里的提问/工具/token 账本）与 Agent/Provider 页，且都属只读"查"能力。
> `/agent/chat`（#8）是既有兼容端点，不删不改语义。

## 2. 逐端点契约

### 2.1 `POST /api/v1/sessions`

请求：`{"profile": "default", "userId": "anonymous"}`（两字段均可空）

响应：`SessionSummary`，`channel=web`、`status=active`。

**幂等**：同一 `(channel=web, userId, profile)` 已有 **active** 会话时，返回已有会话（`sessionId` 不变），
不新建、不报 409、不因 `sessions` 表的唯一约束而 500。
会话已 **archived** 时不满足幂等条件，此时新建一条——归档表示"这段对话结束"，不是"这个身份不能再开新对话"。

> 为什么必须幂等：`sessions` 表对 `(channel, user_id, profile_name)` 有唯一约束，
> 非幂等实现下第二次 POST 会抛 `SQLITE_CONSTRAINT_UNIQUE` 变成 500；
> 而调用方语义本来就是"给我一个能聊的会话"。

### 2.2 `GET /api/v1/sessions`

响应：`SessionSummary[]`，按 `lastActiveAt` **倒序**（最近活跃在前）。

### 2.3 `GET /api/v1/sessions/{id}`

响应：`SessionDetail`（`messages` ≤ 100 条，`messageLimit=100`）。不存在 → 404 `SESSION_NOT_FOUND`。

### 2.4 `POST /api/v1/sessions/{id}/messages`

请求：`{"content": "今天北京天气怎么样"}`

约束：`content` 非空；UTF-8 字节数 ≤ 32768。违反 → 400 `BAD_REQUEST`，且**不调用** `AgentService`。

行为：取会话（不存在 → 404；已归档 → 400 `SESSION_ARCHIVED`）→ `agentService.process(session, content)` → 返回 `MessageResponse`。

### 2.5 `DELETE /api/v1/sessions/{id}`

行为：`sessionManager.archive(id)`；不存在 → 404。响应：`{"sessionId": "...", "status": "archived"}`。

### 2.6 `POST /api/v1/agents/{name}/invoke`

请求：`{"message": "...", "userId": "anonymous"}`

行为：`ProfileRegistry` 中不存在 `name` → 404 `PROFILE_NOT_FOUND`（**不创建会话**）；
存在则创建一次性会话（`channel=invoke`）→ `agentService.process` → `InvokeResponse`。

### 2.7 `POST /api/v1/agent/chat`

保持既有契约（`ChatRequest` / `ChatResponse`）：不传 `sessionId` 时按 `(web, userId, profile)` 复用会话。

### 2.8 `GET /api/v1/profiles` / `GET /api/v1/profiles/{name}`

列表：`ProfileSummary[]`（按 name 排序）。
详情：`ProfileDetail`；不存在 → 404 `PROFILE_NOT_FOUND`。

### 2.9 `GET /api/v1/memory`

响应：`MemorySnapshot`。文件缺失时 `content=""`、`chars=0`（不 500）。

### 2.10 `GET /api/v1/tools` / `GET /api/v1/providers`

工具：`ToolSummary[]`（按 name 排序，来自 `ToolRegistry`）。
Provider：`ProviderSummary[]`（**无 api-key**）。

### 2.11 `GET /api/v1/health` / `GET /api/v1/info`

`health`：`{"status": "UP"}`。
`info`：`InfoResponse`（含 Provider 状态、沙箱策略**条数**、`canBuildAgents`）。

### 2.12 `GET /api/v1/sessions/{id}/runs`

用途：会话的运行明细——管理平台回答"这段对话问了什么、调了哪些工具、花了多少 token"。
数据来自 SQLite 三张审计表（`agent_runs` / `llm_calls` / `tool_invocations`），
进程重启、会话归档后仍可查；**不读内存会话**（消息内容仍由 `2.3` 提供）。

响应 `SessionAudit`：

- `sessionId`；
- `totals`：`{runs, llmCalls, toolCalls, promptTokens, completionTokens, totalTokens, durationMs}`
  —— 前三个是三张表的行数，token 由 `llm_calls` 累加（失败调用没有 token，计 0），
  `durationMs` 为各次提问耗时之和；
- `runs[]`：按 `startedAt` **倒序**（最新在前），每项含
  `runId / userMessage / profileName / channel / provider / model / status / stopReason / success /
  iterations / toolCallCount / promptTokens / completionTokens / totalTokens / durationMs /
  errorMessage / startedAt / completedAt`，以及该次提问下的
  `llmCalls[]`（按发生顺序，**含重试产生的额外尝试**：失败的尝试同样在列，带 `success=false` 与
  `errorMessage`，因此「行数 = 真实请求次数」而「轮数 = `iterations`」）与
  `toolCalls[]`（按时间升序，带参数、结果、成败）。

约束：

- 会话不存在 → 404 `SESSION_NOT_FOUND`（**不返回空账本**——拼错 ID 会被误读成"这段对话没花钱"）；
- `toolCalls[].parameters` ≤ 500 字符；`toolCalls[].result` ≤ 2000 字符，
  被截断时 `resultTruncated=true`（显式标记，不靠长度猜）；
- `status=RUNNING` 的记录 `completedAt` / `durationMs` 为 `null`；
- 只读：核心阶段不提供审计写入、删除、导出。

## 3. 错误映射

| 触发 | 状态码 | errorCode |
|---|---|---|
| `IllegalArgumentException`（空白消息、超 32KB） | 400 | `BAD_REQUEST` |
| 请求体非法 JSON | 400 | `MALFORMED_REQUEST_BODY` |
| `SessionNotFoundException` | 404 | `SESSION_NOT_FOUND` |
| `SessionArchivedException` | 400 | `SESSION_ARCHIVED` |
| `ProfileNotFoundException` | 404 | `PROFILE_NOT_FOUND` |
| `ModelCallException` | 503 | `PROVIDER_UNAVAILABLE` |
| `ProcessingTimeoutException` | 504 | `AGENT_TIMEOUT` |
| `AgentConfigurationException` | 400 | `AGENT_CONFIG_ERROR` |
| 静态资源/路径不存在（`NoResourceFoundException`，含浏览器自动请求的 `/favicon.ico`） | 404 | `RESOURCE_NOT_FOUND`（只记 debug，**不占 ERROR 日志**） |
| 请求方法不支持 | 405 | `METHOD_NOT_ALLOWED` |
| 请求体 `Content-Type` 不支持 | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 其它 Spring MVC 客户端错误（实现 `ErrorResponse` 的 4xx） | 按其自身状态码 | 对应枚举名（如 `BAD_REQUEST`） |
| 其它 | 500 | `INTERNAL_ERROR`（`message="内部错误"`，细节只进日志） |

## 4. 静态资源

| 路径 | 行为 |
|---|---|
| `GET /admin` | 302 → `/admin/index.html` |
| `GET /admin/index.html` | 200 `text/html`（管理平台） |
| `GET /swagger-ui.html` | springdoc 重定向到 Swagger UI |

## 5. Required Verification

| # | 验证项 | 落点 |
|---|---|---|
| 1 | 正常发消息：`agentService.process` 恰一次，响应 `assistant` | `SessionApiControllerTest` |
| 2 | 空白消息 / 超 32KB（UTF-8 字节）→ 400 且引擎零调用 | `SessionApiControllerTest` |
| 3 | 未知会话 → 404；已归档会话发消息 → 400 `SESSION_ARCHIVED` | `SessionApiControllerTest` |
| 4 | 创建会话走 `SessionManager.getOrCreate`（幂等），渠道 `web` | `SessionApiControllerTest` |
| 5 | 列表按 `lastActiveAt` 倒序、详情消息 ≤ 100 条 | `SessionApiControllerTest` |
| 6 | 归档调用 `archive`，未知会话 404 | `SessionApiControllerTest` |
| 7 | invoke 未知 Agent → 404 且不建会话；已知 Agent → 一次调用 + `channel=invoke` | `AgentInvokeTest` |
| 8 | 兼容 `/agent/chat` 行为不回归 | `AgentApiControllerTest`（既有） |
| 9 | 五类异常映射正确、结构三字段 | `GlobalExceptionHandlerTest` |
| 10 | 500 不含内部消息 / 类名 / 堆栈 | `GlobalExceptionHandlerTest` |
| 11 | 真实上下文：health/info/profiles/tools 可达；`/admin/index.html` 200；`/admin` 302 | `WebSmokeTest` |
| 12 | 运行明细：按 `run_id` 分组、token/耗时汇总正确、组内按轮次升序 | `SessionAuditServiceTest` |
| 13 | 运行明细：长结果截断且 `resultTruncated=true`、无记录返回空账本、未知会话 404 | `SessionAuditServiceTest` / `SessionAuditControllerTest` |
| 14 | 未匹配路径（含 `/favicon.ico`）→ 404 `RESOURCE_NOT_FOUND`，不出现 ERROR 日志 | `GlobalExceptionHandlerTest` / `WebSmokeTest` |
| 15 | 同一身份连发两次 `POST /sessions`：返回同一 `sessionId`、列表不增加、不触发唯一约束 500 | `WebSmokeTest`（真实 SQLite） |
| 16 | 全仓 `mvn test` 通过 | 仓库根命令 |
