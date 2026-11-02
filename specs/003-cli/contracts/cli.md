# CLI Contract

**Feature**: CLI 命令行入口与会话管理
**Status**: Implemented（2026-10-09 验证通过：全量测试 + 真实进程手工端到端）

## Root Command

```text
oryxos [--help] <subcommand> [options]
```

- `--help` 展示根命令和 12 个核心命令。
- 未知命令、缺少必需参数或非法参数返回非成功状态，并把诊断写到 stderr。
- 帮助输出不启动完整 Agent 运行时。

**12 个命令的构成**：5 个顶层命令（`init`/`status`/`chat`/`serve`/`gateway`）
+ 4 个 `profile` 子命令（`list`/`create`/`show`/`delete`）
+ 3 个列表命令（`provider list`/`tool list`/`session list`）。

**进程入口**：`io.oryxos.OryxosLauncher`（不是 `OryxOsCli`）。
无 CLI 参数时退化为普通 Spring Boot 启动以保持既有 Web 行为；
带命令名时按轻重分流，`CommandModes` 负责判定，判定过程零 Spring 依赖。

## Core Commands

| Command | Class of work | Inputs | Observable success |
|---|---|---|---|
| `init` | 轻命令 | 可选工作区位置 | 创建或确认工作区，不覆盖已有数据 |
| `status` | 轻命令 | 无或可选工作区位置 | 输出工作区、Profile、Session 和运行状态摘要 |
| `chat` | 重命令 | `--profile <name>`，默认 `default` | 进入 stdin/stdout 多轮对话 |
| `serve` | 重命令 | 可选端口和配置 | 启动 HTTP 服务 |
| `gateway` | 重命令 | 可选 Gateway 配置 | 启动常驻运行模式 |
| `profile list` | 轻命令 | 无 | 列出 Profile，空目录输出空结果 |
| `profile create <name>` | 轻命令 | Profile 名称 | 创建可编辑的 Profile 模板 |
| `profile show <name>` | 轻命令 | Profile 名称 | 输出脱敏后的 Profile 详情 |
| `profile delete <name>` | 轻命令 | Profile 名称 | 删除/标记删除 Profile，不删除历史数据 |
| `provider list` | 轻命令 | 无 | 列出可识别 Provider |
| `tool list` | 轻命令 | 无 | 列出可识别 Tool |
| `session list` | 轻命令 | 可选过滤项 | 列出 Session 摘要，空结果不报堆栈 |

## Chat Interaction

```text
> <user message>
<agent final answer>
> /quit
```

Rules:

1. 启动时使用 `SessionManager.getOrCreate("cli", localUser, profileName)` 的语义获取 Session。
2. 空行或空白行不提交给 Agent。
3. `/quit`（去除首尾空白后）结束交互，不提交给 Agent。
4. 普通消息只调用统一 `AgentService`，由 AgentService 负责 ReAct、模型、Tool、Memory、审计和保存。
5. Agent 失败、Profile 不存在、超时或保存失败时输出脱敏错误并返回非成功状态；不得伪造正常回复。
6. stdin EOF 正常结束当前交互并保留已成功保存的历史。

## Runtime Split

### Light Commands

`init`、`status`、`profile list/create/show/delete`、`provider list`、`tool list`、`session list`：

- 直接读取工作区文件或轻量查询服务。
- 不启动完整 Spring/Agent 运行时。
- 工作区不存在时给出先执行 `init` 的诊断。

### Heavy Commands

`chat`、`serve`、`gateway`：

- 启动一次共享的完整运行上下文。
- 显式保证 Core、Provider、Web/Channel、Storage、JPA Repository 和 Entity 可用。
- 不为不同命令复制 Agent 处理逻辑。

## Error and Redaction Contract

- stderr 必须包含命令、资源名称或路径等定位信息。
- stdout/stderr/logs 不得包含 API key、Provider 凭证或完整系统提示词。
- 持久化失败必须可见；保存错误不得静默转换成成功结果。
- 原始 Agent 错误和保存错误同时发生时，保留原始错误作为主失败原因，并记录保存错误。

## 实现状态与验证

| 契约项 | 实现 | 验证 |
|---|---|---|
| 根命令与 12 个命令可发现 | `OryxOsCli` Picocli 注册 | `CommandHelpTest` |
| 未知命令/非法参数非零退出 | Picocli + 命令内校验 | `RuntimeCommandTest`（`serve --port 0/65536`） |
| 轻命令不启动 Spring | `CommandModes` + `OryxosLauncher` 分支 | `CommandModesTest`；手工观察无 Spring 日志、毫秒级返回 |
| 重命令启动共享运行时 | `SpringRuntimeLauncher` 复用容器内单例 | `SpringRuntimeLauncherTest`；启动日志 `Found 4 JPA repository interfaces` |
| `chat` 会话三元组 | `SessionManager.getOrCreate("cli","local",profile)` | `SessionManagerTest`；手工端到端 |
| 空行/`/quit`/EOF 不提交给 Agent | `CliChannel.handleLine` | `CliChannelTest` |
| Agent 失败不伪造回复 | `CliChannel` 捕获异常并打印脱敏错误 | `CliChannelTest` |
| Profile 详情脱敏 | `ProfileCommand` 过滤敏感关键字行 | `ProfileCommandTest` |
| 轻命令不泄漏凭证 | `profile list`/`provider list` 只列文件名或提示 | `QueryCommandTest` |
| 会话跨重启恢复 | `JpaSessionStore.findByIdentity` + `Session.restore` | `JpaSessionStoreTest`；手工端到端（Ctrl+C 后重启续聊） |

### 已实现边界（避免误读）

| 命令 | 已实现 | 未实现 |
|---|---|---|
| `chat` | 完整交互、多轮、持久化与重启恢复、失败传播 | — |
| `serve` | 以 `WebApplicationType.SERVLET` 启动 Spring，复用 `oryxos-web` 既有 REST 控制器；`--port` 映射为 `--server.port`；常驻 | 无新增 REST 面；未做端口占用前置探测 |
| `gateway` | 以 `WebApplicationType.NONE` 启动共享运行时并常驻 | 未挂载多渠道/路由（IM Channel 属扩展阶段） |

### 输出编码（Windows）

`chat` 的交互文本走 `System.console()`，控制台日志经 `ConsoleEncoding` 接管到宽字符通道，
并在 `application.yaml` 固定 `logging.charset.console=UTF-8`。
无控制台（重定向/管道/IDE 捕获/测试）时全部 no-op，不改变任何行为。
