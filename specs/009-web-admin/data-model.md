# Data Model: Web Service 与第一版管理平台

**Feature**: `009-web-admin` | **Date**: 2026-10-21

无新增持久化实体；本节定义 **API 传输模型**（全部为 JDK record，`oryxos-web/dto`）。

## 1. 会话

| 类型 | 字段 | 说明 |
|---|---|---|
| `CreateSessionRequest` | `profile`（可空，默认 `default`）、`userId`（可空，默认 `anonymous`） | 渠道固定 `web` |
| `SessionSummary` | `sessionId`、`profileName`、`channel`、`userId`、`status`、`messageCount`、`createdAt`、`lastActiveAt` | 列表/详情共用；时间为 ISO-8601 字符串 |
| `SessionDetail` | `session`（`SessionSummary`）、`messages`（`List<MessageView>`，最近 100 条）、`messageLimit`（=100） | 显式声明"最多 100 条" |
| `MessageView` | `role`、`content` | 只读回放 |
| `SendMessageRequest` | `content` | 非空、UTF-8 ≤ 32KB |
| `MessageResponse` | `sessionId`、`role`、`content`、`status` | `role` 固定 `assistant`，`status` 固定 `ok` |

**状态语义**：`active` 可继续发消息；`archived` 视为不可写（发消息 404），列表仍可见。

## 2. Agent 调用

| 类型 | 字段 | 说明 |
|---|---|---|
| `InvokeRequest` | `message`（必填，≤32KB）、`userId`（可空，默认 `anonymous`） | 无状态调用 |
| `InvokeResponse` | `sessionId`、`agent`、`content` | `agent` = Profile 名；会话渠道 `invoke` |

## 3. 查询视图

| 类型 | 字段 |
|---|---|
| `ProfileSummary` | `name`、`description`、`provider`、`model`、`toolCount`、`skillCount`、`scheduleCount` |
| `ProfileDetail` | `name`、`description`、`identity`、`provider`、`model`、`temperature`、`tools`（名字列表）、`skills`（名字列表）、`mcpServers`（名字列表）、`schedules`（`id @ cron` 列表）、`settings`（Map） |
| `ProviderSummary` | `name`、`baseUrl`、`status`（`configured` / `not-assembled`） |
| `ToolSummary` | `name`、`description` |
| `MemorySnapshot` | `content`、`chars`、`note`（固定说明"归档区按 4000 字符截断，与注入上下文口径一致"） |
| `InfoResponse` | `name`、`version`、`description`、`canBuildAgents`、`providers`（`List<ProviderSummary>`）、`sandbox`（`SandboxPolicy`） |
| `InfoResponse.SandboxPolicy` | `enabled`、`allowedPaths`、`allowedCommands`、`allowedDomains`（**均为条数**，不是内容） |

> `ProviderSummary.status`：能拿到 `ChatModel` 即 `configured`；配置里有但未装配为 `not-assembled`
> （用于发现"配置了却没生效"的部署错误）。**任何响应都不含 `api-key`。**

## 4. 错误体

| 字段 | 类型 | 说明 |
|---|---|---|
| `errorCode` | String | `BAD_REQUEST` / `MALFORMED_REQUEST_BODY` / `SESSION_NOT_FOUND` / `PROFILE_NOT_FOUND` / `AGENT_CONFIG_ERROR` / `AGENT_TIMEOUT` / `PROVIDER_UNAVAILABLE` / `INTERNAL_ERROR` |
| `message` | String | 面向调用方的可读说明；500 固定 `内部错误` |
| `timestamp` | String | ISO-8601 |

## 5. 映射与来源

| 视图字段 | 来源 |
|---|---|
| `SessionSummary.*` | `Session` getter；`messageCount` = `getMessages().size()` |
| `SessionDetail.messages` | `Session.getMessages()` 末 100 条（`subList`，不改原列表） |
| `ProfileSummary.*` | `ProfileRegistry.getAllProfiles()` + `Profile.getProvider()/getTools()/getSkills()/getSchedules()` |
| `ProviderSummary.name/baseUrl` | `OryxosProviderProperties.getProviders()` |
| `ProviderSummary.status` | `ProviderModelRegistry.contains(name)` |
| `MemorySnapshot.content` | `MemoryService.snapshot()`（门面只读快照） |
| `InfoResponse.sandbox` | `SandboxProperties`（三个 `size()` + `isEnabled()`） |
| `InfoResponse.canBuildAgents` | `ProviderModelRegistry.size() > 0` |

## 6. 约束与不变量

1. 所有响应 MUST 可由 Jackson 序列化（record + ISO 字符串；不返回 `Instant` 原生类型，避免客户端时区歧义）。
2. 列表端点 MUST 返回空数组而非 `null`（前端 `map` 才不需要判空分支）。
3. 错误响应 MUST 只有三字段，MUST NOT 追加 `path` / `trace` / `exception`。
4. 任何响应 MUST NOT 含 `api-key`、绝对文件路径、白名单内容。
