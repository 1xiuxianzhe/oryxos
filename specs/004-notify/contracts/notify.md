# Notify Contract

**Feature**: Notify 出站通知模块
**Status**: Implemented — 本契约的 12 条 Required Verification 均已由自动化测试覆盖并通过，
另经使用方在真实环境完成人工验收（真实 webhook 推送）。第 9 条（未装配校验器时告警）
有明确的断言限制，见第 6 节末尾。

## 1. 渠道抽象契约（接口先行）

```java
public interface NotifyChannelAdapter {
    void send(NotifyTarget target, String content);
}
```

**中立性要求（可评审项，SC-002）**：接口的方法名、参数类型、参数名中**不得**出现具体渠道或协议词汇。自查方式：把实现换成"企业微信官方 SDK 实现"或"飞书实现"，此签名是否需要改动？答案必须是不需要。

| 规则 | 说明 |
|---|---|
| 参数只有"目标 + 内容" | 不得增加 `webhookUrl`、`accessToken`、`signature` 这类参数 |
| 不返回渠道特有的响应体 | 方法返回 `void`；需要判定成败时**抛异常**，不返回 HTTP 状态码或响应 JSON |
| 失败即异常 | 任何非成功结果（超时、5xx、拒绝）必须抛异常，**不得**静默返回 |

## 2. 通知目标契约

```java
public record NotifyTarget(String channelType, Map<String, String> config) {
}
```

| 规则 | 说明 |
|---|---|
| 只携带"去哪儿" | 不含发送结果、不含重试状态 |
| `config` 值统一为字符串 | 渠道特有的值类型转换由解析器完成，发送侧不做类型分支 |
| 不可变 | 构造后 `config` 不得再被修改 |

## 3. Webhook 实现契约

| 项 | 约定 |
|---|---|
| 方法 | `POST` |
| `Content-Type` | `application/json` |
| 请求体 | `{"content": "<内容>"}` |
| URL 来源 | **只能**取自 `target.config().get("url")`；不得硬编码、不得从环境变量兜底 |
| 超时 | 连接 10s、请求 30s（与既有 `HttpTool` 一致） |
| 2xx | 正常返回 |
| 非 2xx | 抛异常，异常信息含状态码，**不含完整 URL**（见第 5 节） |
| 网络异常/超时 | 抛异常，信息含异常类型与简短原因 |

## 4. notify 工具契约

| 项 | 值 |
|---|---|
| 工具名 | `notify` |
| 描述 | 把一条消息推送到当前 Agent 配置好的通知渠道 |
| 入参 schema | `{"type":"object","properties":{"content":{"type":"string",...},"channel":{"type":"string",...}},"required":["content"]}` |
| 接入方式 | `implements OryxTool`，**不使用** Spring AI 的 `@Tool` 注解 |
| 注册方式 | 由装配配置显式 `ToolRegistry.register(...)` |
| 渠道来源 | `AgentContext.current()` 取当前 Profile，**不得**从入参或全局配置取 |

### 固定执行顺序（不变式）

```text
1. 取当前 Profile（AgentContext）
2. 解析 NotifyTarget（可能因配置问题失败）
3. 安全校验（若已装配校验器）
4. 发送
```

- 第 3 步**永远早于**第 4 步。
- 第 2 或第 3 步失败时，第 4 步的调用次数为 **0**。
- 此顺序必须由自动化测试以"调用顺序断言"钉死，不接受"代码评审目测"。

### 返回语义

| 情况 | 返回 | retryable |
|---|---|---|
| 推送成功 | `ToolResult.ok("已推送")` | — |
| 未配置渠道 / 渠道不存在 / 类型不支持 / 缺 url / url 非法 | `ToolResult.fail(原因, false)` | `false` |
| `content` 为空或纯空白 | `ToolResult.fail(原因, false)` | `false` |
| 白名单校验不通过 | `ToolResult.fail(原因, false)` | `false` |
| 网络异常 / 超时 / 5xx | `ToolResult.fail(原因, true)` | `true` |
| 目标 4xx | `ToolResult.fail(原因, false)` | `false` |

**任何情况下都不得**把失败改写成 `ok`。

## 5. 错误与脱敏契约

- 错误信息 MUST 含定位信息：渠道类型、缺失的配置项名、失败类别。
- 错误信息 MUST NOT 含完整 webhook 地址或其查询串凭证；只允许出现 host 或脱敏形式。
- 日志 MUST NOT 打印完整通知地址。
- Profile 详情展示 MUST 对含 `url` 的行做遮挡（同时覆盖内嵌 `key=` 的形式）。

## 6. Required Verification

| # | 要求 | 计划验证方式 |
|---|---|---|
| 1 | 发送能到达 `NotifyTarget` 指定的 URL，请求体带 `content` | `WebhookNotifyAdapterTest`：本地假 webhook 断言方法/体 |
| 2 | URL 来自配置而非硬编码 | 同上：换 URL 再发一次，两次命中的服务不同 |
| 3 | 非 2xx 时异常向上抛 | 同上：假服务返回 500 |
| 4 | 渠道缺省取第一个 | `NotifyTargetResolverTest` |
| 5 | 未配置渠道 → 明确失败 | `NotifyTargetResolverTest` + `NotifyToolTest` |
| 6 | 渠道不存在 / 类型不支持 / 缺 url / url 非法 → 明确失败 | `NotifyTargetResolverTest`（含边界用例表） |
| 7 | 校验先于发送（顺序断言） | `NotifyToolTest`：`InOrder` 断言 |
| 8 | 校验失败时 adapter 调用次数为 0 | `NotifyToolTest`：`verify(adapter, never())` |
| 9 | 未装配校验器时放行但告警 | `NotifyToolTest` 断言"放行且仍然发送"；**告警文本**未自动化断言（见下方说明） |
| 10 | `notify` 出现在工具注册表中 | `NotifyAutoConfigurationTest` 或启动冒烟 |
| 11 | Profile 详情不输出 webhook 地址 | `ProfileCommandTest`（`oryxos-cli`） |
| 12 | 错误信息不含完整地址 | `WebhookNotifyAdapterTest` / `NotifyToolTest` 断言 |

### 关于第 9 条的断言限制

`NotifyToolTest` 覆盖的是"未装配校验器时**放行且仍然发送**"这个可观测行为；
但"输出 WARN 日志"本身没有做自动化断言。

原因：`oryxos-tool` 的测试类路径上只有 `slf4j-api`，没有日志实现（无 logback），
SLF4J 会退回 NOP 实现，日志不可观测。要断言它需要为这一个断言引入日志后端依赖，
代价大于收益。

因此对该要求采取"代码保证 + 人工确认"：WARN 语句在 `NotifyTool.guard(...)` 中，
由 `volatile boolean` 抑制重复输出。此处如实标注，避免被读成"已自动化覆盖"。

需要澄清一个措辞：它是**抑制刷屏，不是严格的"恰好一次"**。`volatile` 只保证可见性，
`if (!logged) { logged = true; logger.warn(...); }` 在并发首次调用时理论上可能输出多行。
影响仅限于日志条数，不改变任何行为，故不为它引入锁或 `AtomicBoolean`。
