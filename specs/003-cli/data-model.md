# Data Model: CLI 命令行入口与会话管理

**Date**: 2026-09-28
**Feature**: [spec.md](./spec.md)

## Workspace

表示当前 OryxOS 实例的本地工作目录。

| 字段/部分 | 说明 | 约束 |
|---|---|---|
| root | 工作区根路径，默认是当前目录下 `.oryxos/` | 初始化前不得假设目录存在 |
| profiles | Profile YAML 目录 | `profile list/create/show/delete` 使用 |
| memory | 长期记忆目录 | 初始化时创建 `MEMORY.md` |
| skills | Skill 文档目录 | 本特性只创建/保留目录，不实现 Skill 解析 |
| sessions | Session 数据位置 | 与 SQLite 存储约定一致 |
| logs | 运行日志目录 | 不输出密钥和完整系统提示词 |
| bootstrap | `AGENTS.md`、`SOUL.md`、`USER.md` 等启动文件 | `init` 创建默认模板 |
| database | SQLite 数据库文件 | 默认 `.oryxos/oryxos.db` |

### Workspace State Transitions

```text
ABSENT --init--> INITIALIZED
INITIALIZED --init--> INITIALIZED (no destructive overwrite)
```

## Profile

表示可被 CLI 选择的 Agent 配置。

| 字段 | 说明 | 约束 |
|---|---|---|
| name | 唯一 Profile 名称 | 不为空，创建时不能重复 |
| description | 可读描述 | 列表和详情可展示 |
| identity | Agent 身份和提示词 | 详情输出须脱敏 |
| provider | Provider 名称、模型和参数 | 引用必须可解析 |
| tools | 允许使用的 Tool 名称集合 | 只展示名称，不展示凭证 |
| channels | 支持的消息入口 | `cli` 是本特性核心入口 |
| bootstrap | Bootstrap 文件引用 | 缺失时返回明确诊断 |
| settings | 最大迭代、历史等运行参数 | 使用现有 Profile 校验约定 |

### Profile Lifecycle

```text
MISSING --create--> ACTIVE
ACTIVE --show/list--> ACTIVE
ACTIVE --delete--> DELETED/ABSENT
```

删除 Profile 不删除其已有 Session、LLM 调用或 Tool 调用审计数据。

## Session

表示同一渠道、用户和 Profile 下的连续对话。

| 字段 | 类型/表达 | 约束 |
|---|---|---|
| sessionId | 稳定字符串 | 由 `SessionManager` 统一生成；入口不得自行拼接 |
| channel | 字符串 | CLI 使用 `cli`；不同值必须隔离 |
| userId | 字符串 | 本地 CLI 使用稳定的本地用户标识；不同值必须隔离 |
| profileName | 字符串 | 关联 Profile；不同值必须隔离 |
| messages | 有序 Message 列表 | 持久化为 JSON；保留角色和内容 |
| status | `ACTIVE` / `ARCHIVED` | 新会话为 `ACTIVE` |
| createdAt | 时间戳 | 创建时设置 |
| lastActiveAt | 时间戳 | 每次成功追加消息后更新 |
| archivedAt | 可空时间戳 | 归档时设置 |

### Session Identity and Invariants

- 唯一业务键为 `(channel, userId, profileName)`；同一业务键的 `getOrCreate` 必须返回同一 Session。
- 三元组任一字段不同，必须返回不同 Session。
- `sessionId` 的格式由 `SessionManager` 内部决定，CLI、Web 和 Gateway 不重复实现。
- `getOrCreate` 必须先查内存索引，再查持久化 Store，最后才创建新 Session。
- 并发创建同一业务键时，持久化层必须以唯一约束或等价机制避免生成两条活动会话。

## Message

Session 中的一条消息，至少包含：

| 字段 | 说明 |
|---|---|
| role | `user`、`assistant` 或 `tool` |
| content | 消息正文或工具结果 |
| name | 可选的 Tool 名称 |
| timestamp | 可选的消息时间 |

消息列表序列化和反序列化必须保持原顺序、角色和内容完整。

## Run Mode

| 模式 | 输入来源 | 运行能力 | Session 约定 |
|---|---|---|---|
| `chat` | stdin | 完整 Agent 运行时 | `channel=cli` |
| `serve` | HTTP | 完整 Web/Agent 运行时 | 复用统一 Session 存储 |
| `gateway` | 常驻 Channel | 完整 Agent/Channel 运行时 | 复用统一 Session 存储 |

## CLI Result

每个命令产生以下可观察结果：

- stdout：成功结果、列表、提示符或最终回复。
- stderr：参数、配置、运行时和持久化错误。
- exit code：成功为成功状态；失败为非成功状态。
- logs：关联命令、Profile、Session 和处理结果，但不包含凭证。
