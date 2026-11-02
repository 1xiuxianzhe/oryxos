# Data Model: Notify 出站通知模块

**Feature**: `004-notify` | **Date**: 2026-10-09

本特性**不新增持久化结构**（无新表、无新文件）。此处定义的是内存中的数据结构、解析规则与错误目录。

## 1. NotifyTarget（新增，`io.oryxos.tool.notify`）

一次推送的"寄送地址"，对外抽象中唯一携带"去哪儿"语义的载体。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `channelType` | `String` | 是 | 渠道类型标识，如 `webhook`。决定由哪档 adapter 解释 `config` |
| `config` | `Map<String, String>` | 是 | 渠道特定配置。`webhook` 档要求含 `url` |

约束：

- 值统一为 `String`：HTTP 场景只需要字符串，避免在发送侧做类型分支。
- 对 `channelType` 本身不做枚举校验——校验发生在解析阶段（见第 3 节），发送侧只按类型分发。
- 不可变（`record`），构造后其 `config` 需为不可变映射。

## 2. NotifyChannelConfig（既有，`io.oryxos.core.profile.Profile`）

**已存在**，本特性开始真正消费。字段为：

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `String` | 渠道类型，对应 `NotifyTarget.channelType` |
| `config` | `Map<String, Object>` | 渠道特定配置；`webhook` 档需含 `url` |

Profile 中的声明形态：`Profile.notifyChannels`，类型 `List<NotifyChannelConfig>`，可为 `null` / 空 / 多项。

> **注意**：`config` 是 `Map<String,Object>`（YAML 反序列化需要），与 `NotifyTarget.config` 的 `Map<String,String>` 不同——转换由解析器统一完成，见 `plan.md` D-02。

## 3. 解析规则（NotifyTargetResolver）

输入：当前 `Profile` + 调用方给的渠道名（可缺省）。输出：`NotifyTarget`，或抛 `NotifyConfigException`。

### 3.1 渠道选择

| 输入 | 规则 | 结果 |
|---|---|---|
| `channel` 为 `null` / 空 / 空白 | 取**第一个**已配置渠道 | 命中该项 |
| `channel` 非空 | 与各项 `type` 做**忽略大小写**的精确匹配 | 取**第一个**匹配项 |
| `channel` 非空且无任何匹配 | 失败 | 报错并列出可用 `type` |

设计取舍：只按 `type` 匹配，不引入"渠道名"这一层新概念。核心阶段的现实场景是"一个 Profile 配一个群机器人"或"配几个不同类型的机器人"，用 `type` 足以区分，且不需要在 Profile 里额外起名。

### 3.2 校验顺序与失败目录

按以下顺序检查，**首个失败即返回**：

| # | 条件 | 异常信息要点 |
|---|---|---|
| 1 | `Profile` 为 `null` | 无处理上下文，无法解析通知渠道 |
| 2 | `notifyChannels` 为 `null` 或空 | 当前 Agent 未配置通知渠道 |
| 3 | 指定 `channel` 查无匹配 | 渠道 `<name>` 未配置，可用渠道：`[type...]` |
| 4 | `type` 为空白 | 渠道配置缺少类型 |
| 5 | `type` 不在支持列表（当前仅 `webhook`） | 不支持的渠道类型 `<type>` |
| 6 | `config` 为 `null` 或缺 `url` | 渠道 `<type>` 缺少 url 配置 |
| 7 | `url` 为空白 | 同上（与 6 合并处理） |
| 8 | `url` 不是合法 HTTP(S) 地址 | 渠道 `<type>` 的 url 非法 |

> 第 8 条只做**最低限度**的形态校验（有 scheme 且为 `http`/`https`、有 host），不做可达性校验——可达性由实际请求去暴露，避免解析阶段引入网络调用。

### 3.3 支持的渠道类型

| 类型 | core 阶段 | 说明 |
|---|---|---|
| `webhook` | ✅ 支持 | 通用 HTTP POST JSON |
| 其它（`feishu` / `wecom` / `dingtalk` …） | ❌ 明确拒绝 | 报"不支持的渠道类型"，**不得静默按 webhook 处理** |

## 4. 安全校验器（复用既有 `SandboxChecker`）

| 项 | 内容 |
|---|---|
| 复用方法 | `checkHttpUrl(String url)` |
| 语义陷阱 | `allowedDomains` 为空时 `anyMatch` 恒为 `false` → **全拒** |
| 本特性的接入方式 | `ObjectProvider<SandboxChecker>`，**可选** |
| 已装配 | 发送前调用；违规抛 `SandboxViolationException` → 转失败结果 |
| 未装配 | 记 WARN「出站白名单未生效」后放行 |
| 不变式 | 校验调用**永远早于**发送调用；校验失败时发送调用次数为 0 |

## 5. 工具契约数据（notify 工具）

| 项 | 值 |
|---|---|
| `getName()` | `notify` |
| `getDescription()` | 把一条消息推送到当前 Agent 配置好的通知渠道 |
| 入参 `content` | `string`，**必填**，非空白 |
| 入参 `channel` | `string`，可选；缺省用第一个渠道 |
| 成功返回 | `ToolResult.ok("已推送")` |
| 失败返回 | `ToolResult.fail(原因, retryable)`；网络类失败可重试，配置类失败不可重试 |

### 可重试性判定

| 失败类别 | retryable |
|---|---|
| 未配置渠道 / 渠道不存在 / 类型不支持 / 缺 url / url 非法 / 内容为空 | `false`（配置问题，重试无用） |
| 网络异常 / 超时 / 目标返回 5xx | `true` |
| 目标返回 4xx | `false`（请求本身有问题） |

## 6. 状态机

无。本特性全部为无状态操作：解析 → 校验 → 发送 → 返回。不持有跨调用的状态，不写数据库，不产生后台线程。

## 7. 与既有数据的关系

```text
Profile（既有）
  └── notifyChannels: List<NotifyChannelConfig>       ← 本特性开始消费
         └── 解析 ──> NotifyTarget（新增，发送侧载体）
                        └── 校验（SandboxChecker，既有，可选）
                               └── 发送（WebhookNotifyAdapter，新增）

tool_invocations（既有审计表）
  └── notify 的每次调用由既有 ToolExecutor 自动记录，本特性不绕过、不额外写
```
