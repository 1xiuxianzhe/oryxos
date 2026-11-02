# Session Store Contract

**Feature**: CLI 命令行入口与会话管理
**Status**: Implemented（2026-10-09）

**实现位置**：端口 `io.oryxos.core.session.SessionStore`；
适配器 `io.oryxos.storage.session.JpaSessionStore`（Spring `@Component`，构造注入 `SessionRepository`）；
唯一性保护 `db/schema.sql` 的 `uq_sessions_active_identity`
（`(channel, user_id, profile_name) WHERE status = 'active'` 部分唯一索引）。

## Core Port

`SessionStore` remains a Core-owned port. Storage adapters implement it without creating a Core-to-Storage dependency.

Required semantics:

- `findById(sessionId)` returns the persisted Session when present.
- `findByIdentity(channel, userId, profileName)` returns the active Session for the business identity when present.
- `save(session)` inserts or updates the complete Session, including ordered message history.
- `list(...)` supports the `session list` command with safe summaries.

## Identity Invariants

- The persisted identity `(channel, userId, profileName)` is unique for an active Session.
- Lookup and creation are idempotent for the same identity.
- Identity changes produce a different Session.
- Profile deletion does not cascade-delete Session history.

## Persistence Contract

- Session messages are serialized as JSON in the existing `messages_json` field.
- Read-after-write preserves message role, content, tool name where present, and order.
- A new application context can read data written by a previous context.
- Schema creation and evolution use the existing manual SQLite schema/migration convention; implementation must not rely on SQLite `ddl-auto=update` for structural evolution.
- Persistence failures are propagated to the caller or represented by a typed failure that the CLI can turn into a non-success exit result; they must not be silently swallowed.

## Required Verification

| # | 要求 | 状态 | 证据 |
|---|---|---|---|
| 1 | 同一身份两次返回同一 Session ID | ✅ 已覆盖 | `SessionManagerTest.相同业务身份复用同一会话` |
| 2 | `channel`/`userId`/`profileName` 任一不同即隔离 | ✅ 已覆盖 | `SessionManagerTest.业务身份任一字段变化都会隔离会话` |
| 3 | 保存并重载后消息与元数据完整 | ✅ 已覆盖 | `JpaSessionStoreTest.手工脚本建表_会话与完整历史能存能读回`（角色、顺序、内容、时间戳） |
| 4 | 新上下文重新打开后可重载 | ✅ 已覆盖 | `JpaSessionStoreTest`（`flush + clear` 后仅从库读回）；`SessionManagerTest`（新 `SessionManager` 按身份恢复）；另有真实进程重启的手工端到端验证 |
| 5 | 存储失败对调用方可见 | ⚠️ 部分 | 序列化/反序列化失败已上抛 `IllegalStateException`；缺"写入失败可见"的自动化断言（见 `tasks.md` T026/T035） |
| 6 | Profile 删除后历史仍存在 | ⚠️ 部分 | `profile delete` 只删配置文件、不触碰任何表；缺自动化回归（见 `tasks.md` T038） |

## 实现备注

- **消息序列化**：`messages_json` 存 JSON 数组，元素为 `{"role","content"}`；`Session.restore()` 负责重建。
  读取顺序与写入顺序一致（测试断言 `containsExactly`）。
- **时间戳**：`createdAt`/`lastActiveAt`/`archivedAt` 为 `Instant`，SQLite 列亲和性不当会导致读回解析失败，
  已在测试中固定断言往返正确。
- **保存语义**：`save()` 先按 `sessionId` 查已有记录再更新，是 upsert 而非插入（测试断言 `repository.count() == 1`）。
- **异常策略**：序列化失败与读取失败都向调用方抛异常，不再静默吞掉。
  但 `AgentService.persistQuietly` 会捕获保存异常并记 ERROR——这是刻意的：
  不能用一个保存异常掩盖原本的业务异常。
- **测试隔离**：`JpaSessionStoreTest` 每次运行使用唯一库文件名（`target/jpa-session-store-test-<uuid>.db`）。
  复用固定文件名时，Windows 下若上轮 JVM/IDE 仍持有 SQLite 文件句柄，删除会失败并让整个测试类报错。
