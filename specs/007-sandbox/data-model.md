# Data Model: Sandbox 统一抽象与白名单校验

**Feature**: `007-sandbox` | **Date**: 2026-10-20

本特性无持久化实体（不新增表、不改表结构），"数据模型"指类型模型与配置模型。

## 1. 类型模型

```text
              ┌───────────────────────────┐
              │        Sandbox            │   <<interface>>
              │  enforce(SandboxAction)   │
              └─────────────┬─────────────┘
                            │ implements
              ┌─────────────▼─────────────┐
              │     WhitelistSandbox      │
              │ - allowedPaths: List<Path>│  (每次校验时解析真实路径)
              │ - allowedCommands: Set    │
              │ - allowedDomains: List<Pattern>
              │ - enabled: boolean        │
              │ + enforce(action)         │
              │ - checkFilePath(...)      │  private
              │ - checkShellCommand(...)  │  private
              │ - checkHttpUrl(...)       │  private
              └───────────────────────────┘

  SandboxAction(type, target)  ──used by──▶  Sandbox
  ActionType: FILE_ACCESS | SHELL_EXEC | HTTP_REQUEST
  SandboxViolationException extends RuntimeException
```

### 1.1 `Sandbox`

| 项 | 内容 |
|---|---|
| 方法 | `void enforce(SandboxAction action)` |
| 成功 | 正常返回（无返回值，无副作用） |
| 失败 | 抛 `SandboxViolationException`（RuntimeException） |
| 约定 | 除该方法外**不得**暴露任何按维度划分的方法；签名中不得出现实现特有概念 |
| 实现数 | 核心阶段唯一：`WhitelistSandbox` |

### 1.2 `SandboxAction`（record）

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `ActionType` | 决定 `target` 如何被解释 |
| `target` | `String` | 文件路径 / 命令串 / URL，纯字符串，实现自行解释 |

约束：不含白名单、容器、镜像、路径前缀、域名后缀等任何实现概念。

### 1.3 `ActionType`（enum）

| 值 | 语义 | 由谁产生 |
|---|---|---|
| `FILE_ACCESS` | 访问一个文件系统路径 | `FileTool`（read/list/write） |
| `SHELL_EXEC` | 执行一条命令 | `ShellTool` |
| `HTTP_REQUEST` | 发起一次出站 HTTP 请求 | `HttpTool`、`NotifyTool` |

### 1.4 `SandboxViolationException`

| 项 | 内容 |
|---|---|
| 类型 | `RuntimeException`（不检查异常，避免污染所有调用签名） |
| 消息 | 必含**维度 + 被拒值**，例：`Shell 命令不在白名单内: rm` |
| 用途 | 既给模型看（下一轮改道），也给运维看（`tool_invocations.error_message`） |

## 2. 校验规则矩阵

| 维度 | 输入 | 判定 | 空清单 | 拒绝消息示例 |
|---|---|---|---|---|
| 文件 | `target` 路径（可能不存在） | 解析真实路径 → 与白名单条目的真实路径比对，`startsWith` 判定 | 全拒 | `文件路径不在白名单内: /etc/passwd` |
| 命令 | `target` 命令串 | `trim` 后取首个 token，与清单**精确**匹配（大小写敏感） | 全拒 | `Shell 命令不在白名单内: rm` |
| 域名 | `target` URL | 提取 host → 与通配模式（`*`/`?`，`.` 转义）整体匹配 | 全拒 | `HTTP 域名不在白名单内: evil.example.net` |

### 2.1 路径解析规则（保留 005 的实测修正）

1. `normalize().toAbsolutePath()` 得到绝对路径；
2. 目标存在 → `toRealPath()`；
3. 目标不存在 → 找到**最近的已存在祖先**，取它的真实路径，再拼回剩余段；
4. 解析抛 `IOException` → 退回字面绝对路径（保守）。
5. 白名单条目与待校验路径**走同一套解析**，避免"配了符号链接目录却匹配不上"。

## 3. 配置模型（`oryxos.sandbox`）

| 键 | 类型 | 默认 | 语义 |
|---|---|---|---|
| `enabled` | boolean | `true` | `false` 时三维度全部放行；启动告警；**格式校验仍执行** |
| `allowed-paths` | List&lt;String&gt; | `[]` | 文件类允许的目录前缀（按真实路径比对）；空 = 全拒 |
| `allowed-commands` | List&lt;String&gt; | `[]` | 命令首 token 白名单；空 = 全拒 |
| `allowed-domains` | List&lt;String&gt; | `[]` | 出站域名白名单，支持 `*.` 通配；空 = 全拒 |

**校验时机**：空白条目在启动阶段报 `IllegalStateException`（空字符串路径会被规范化成当前目录，
等于静默放宽白名单）。

## 4. 参数传递链

```text
application.yaml (oryxos.sandbox.*)
        │  @ConfigurationProperties
        ▼
SandboxProperties ──▶ ToolAutoConfiguration.sandbox(props) ──▶ Sandbox(WhitelistSandbox)
                                                                    │
   ┌──────────────────────┬──────────────────────┬────────────────┴────────┐
   ▼                      ▼                      ▼                         ▼
FileTool              ShellTool              HttpTool                  NotifyTool
enforce(FILE_ACCESS)  enforce(SHELL_EXEC)    enforce(HTTP_REQUEST)     enforce(HTTP_REQUEST)
```

四个工具持有的静态类型一律是 `Sandbox`（接口），不出现 `WhitelistSandbox`。
