# Research: Notify 出站通知模块

**Feature**: `004-notify` | **Date**: 2026-10-09

本文件记录"源文档示意代码 → 仓库实际架构"的每一处调研结论与取舍依据。结论对应 `plan.md` 的 D-01 ~ D-08。

## 调研方法

1. 读源文档 `docs/class/第19节：Notify 模块 原理解析、实现与代码讲解.md`，提取其**意图**（而非字面代码）。
2. 全仓检索源文档引用的每个类型，确认哪些**已存在**、哪些**不存在**。
3. 对已存在的类型读其真实签名与语义（尤其是边界行为），据此校正设计。

## 事实核查表

| 源文档引用 | 仓库真实情况 | 影响的决策 |
|---|---|---|
| `ProfileContext`（17 节 ThreadLocal） | 实际叫 `AgentContext`，静态 ThreadLocal，暴露 `current()` 返回 `Profile`；**没有** `resolveNotifyChannel` | D-05 |
| `@Tool` 注解 + `@Component` 自动装配 | 全仓**零使用**；统一 `OryxTool`（`getName/getDescription/getInputSchema/execute(Map)`） | D-01 |
| 工具会被框架自动纳入 | `ToolRegistry` 只有手动 `register()`，**无 Spring 自动收集**；启动时注册表为空 | D-07 |
| `ToolResult.success(...)` | 实际工厂方法是 `ToolResult.ok(String)` / `fail(String, boolean retryable)` | — |
| `Sandbox` / `SandboxAction` / `ActionType` | **不存在**；真实是 `SandboxChecker.checkFilePath/checkShellCommand/checkHttpUrl` | D-04 |
| `SandboxChecker` 是 Spring bean | 不是 bean；白名单**只能**从构造函数三个 `List` 传入，全仓**没有任何一处构造它**，也没有配置来源 | D-04 |
| Spring `RestClient` | 全仓零使用；既有 `HttpTool` 用 JDK `java.net.http.HttpClient` | D-03 |
| MockWebServer / WireMock | 全仓零使用，无依赖 | D-06 |
| `notify_channels` 字段 | **已存在**于 `Profile`：`List<NotifyChannelConfig>`，结构为 `type:String` + `config:Map<String,Object>`；`ProfileLoader` **不校验**该字段 | D-02、D-08 |
| 包 `io.oryxos.tool.notify` | **不存在**，本特性新建 | — |

## D-01 抽象落点

**问题**：接口、数据载体、实现、工具类分别放哪？

**决策**：全部落 `oryxos-tool`，包 `io.oryxos.tool.notify`（工具类按既有习惯放 `io.oryxos.tool.builtin`）。

**依据**：Constitution 原则 IV 明确"所有 Tool 相关代码必须合并到 `oryxos-tool` 模块，不拆分成多个子模块"。`oryxos-tool` 现有 `builtin/` 与 `sandbox/` 两个包，新增 `notify/` 与 `config/` 与之平级。

**替代方案**：放 `oryxos-core`（被否——core 不该承载具体渠道实现，且会造成 core→tool 的概念倒置）；新建 `oryxos-notify` 模块（被否——违反 IV）。

## D-02 为什么不让 `NotifyTarget` 直接复用 `Profile.NotifyChannelConfig`

**问题**：两者字段高度重合，能不能合一？

**决策**：分开。`NotifyTarget` 是 `record(channelType, Map<String,String> config)`；解析器负责从配置模型转换。

**依据**：`Profile.NotifyChannelConfig.config` 是 `Map<String,Object>`（YAML 反序列化需要），若抽象直接暴露它，等于把"配置模型"焊进"发送抽象"。将来某档实现需要布尔/整型配置时，要么改配置模型（影响所有 Profile），要么在发送侧做 `instanceof` 判断（把渠道细节泄漏回抽象）。转换只花一行，换来抽象与配置解耦。

**替代方案**：直接传 `Map<String,Object>` 给 adapter（被否，见上）；把 `NotifyTarget` 定义成接口（被否，`record` 足够且语义是纯数据）。

## D-03 HTTP 客户端选型

**决策**：JDK `java.net.http.HttpClient`。

**依据**：
1. 既有 `HttpTool` 用的就是它，同类工具超时取值保持一致（连接 10s / 请求 30s），避免行为漂移；
2. `oryxos-tool` 的依赖只有 `oryxos-core` + `spring-ai-commons` + `mcp`，**不保证**有 `spring-web`，用 `RestClient` 需要新增依赖；
3. 本特性只有一个出站调用点，标准库足够。

**替代方案**：Spring `RestClient`（被否，需新增依赖且与既有工具不一致）；`HttpURLConnection`（被否，API 陈旧、无超时精细控制）。

## D-04 安全校验：为什么会做成"可选"

**问题**：源文档要求"发送前先过白名单"，但仓库的白名单来源不存在。

**关键发现**：`SandboxChecker.checkHttpUrl` 用的是 `allowedDomains.stream().anyMatch(...)`，**空白名单下 `anyMatch` 恒为 false**，即语义是**全拒**而非全放。

**决策**：`NotifyTool` 通过 `ObjectProvider<SandboxChecker>` 接收**可选**校验器；装配则校验、未装配则 WARN 放行。

**依据**：
- 源文档自己说"安全校验先占位，具体怎么做留给 Sandbox 那节"，即本特性只需保证**调用点存在且位于发送之前**；
- 仓库已有同构先例：`ToolInvocationValidator` + `NoopToolInvocationValidator`，由 `ToolExecutor` 在使用点调用，真实规则留给后续能力；
- 若做成必需依赖，开箱即用的 `notify` 会因空白名单 100% 失败，特性不可演示。

**显式记录的残留风险**：Sandbox 特性落地前白名单实际未生效，WARN 日志是唯一提示。不隐藏这一点，见 `plan.md` D-04 与与 spec.md A-003。

**替代方案**：强制从 `oryxos.sandbox.allowed-domains` 读取并**必须**配置（被否——那是 Sandbox 特性的设计工作，会越界并让本特性无法独立验收）；自造 `NotifyGuard` 接口（被否——与既有 `SandboxChecker` 重复抽象）。

## D-05 渠道解析为什么不能在 `AgentContext` 上开方法

**问题**：源文档的写法是 `profileContext.resolveNotifyChannel(channel)`。

**决策**：新增 `NotifyTargetResolver`（`oryxos-tool`），`NotifyTool` 自己 `AgentContext.current()` 取 Profile 再交给它。

**依据**：`AgentContext` 在 `oryxos-core`，`NotifyTarget` 在 `oryxos-tool`。若让 `AgentContext` 返回 `NotifyTarget`，core 就必须依赖 tool 的类型——**反向依赖**，违反"Core 不得反向依赖上层"的分层约束（`oryxos-tool` 的 pom 已声明依赖 `oryxos-core`）。

**替代方案**：把 `NotifyTarget` 下沉到 core（被否——渠道抽象属于 Tool 体系，按 IV 应在 tool 模块）；在 core 定义泛型化的"通知渠道配置访问器"（被否——`Profile` 已有 `getNotifyChannels()`，再包一层没有收益）。

## D-06 测试假 webhook 的实现

**决策**：JDK 自带 `com.sun.net.httpserver.HttpServer`，随机端口，封装成测试脚手架 `FakeWebhookServer`。

**依据**：源文档要求 MockWebServer 的**意图**是"本地起来的假 webhook，不算外网依赖，仍是单测层"。JDK `HttpServer` 完全满足：本地回环、离线、可断言请求方法/路径/体、可构造 5xx 与慢响应。代价为 0 新依赖，而 MockWebServer 会连带 okhttp + kotlin-stdlib。

**替代方案**：MockWebServer（被否，新依赖）；`MockRestServiceServer`（被否，它绑定 Spring 的 `RestTemplate`/`RestClient`，与本特性的 JDK `HttpClient` 不兼容）。

## D-07 工具注册装配

**决策**：`oryxos-tool` 新增 `@Configuration`，装配 adapter + resolver + tool，并 `ToolRegistry.register(tool)`；**只注册 `notify`**。

**依据**：
- 注册表当前为空，注册 `notify` 是净增能力、零回归；
- Agent 要能调用工具，工具必须出现在 `PromptBuilder` 读取的 `toolRegistry.listAll()` 里，否则特性不可演示（违反项目"每个 user story 有可演示 demo"的原则）。

**替代方案**：不注册，留给后续章节（被否——仓库已有的依赖件齐备，没有推迟的技术理由，只会让特性变成"写完但用不上"）；顺带把 `FileTool`/`ShellTool`/`HttpTool` 一起注册（被否——属既有缺口的独立立项，混进本特性会扩大回归面）。

## D-08 脱敏范围

**决策**：把 `url` 加入 Profile 详情的脱敏关键字。

**依据**：webhook 地址通常内嵌凭证（企业微信 `...?key=<密钥>`）。现有脱敏是按关键字过滤行的，`key=` 形式会被命中，但 `https://host/hook/abc123` 形式不会——后者同样是可被滥用的凭证。

**代价**：provider 的 `base-url` 也会被遮挡。接受：`base-url` 对排查无足轻重，而漏掉一个 webhook 地址等于交出群组推送权限。

**替代方案**：只对 `notify_channels` 段做定向脱敏（被否——需要引入 YAML 结构感知，改动面更大，而收益仅是保住一行 `base-url` 的可读性）。

## 未决/后续

- **统一 Sandbox 设计**（23/24 节）：三类白名单、配置来源、强制启用。本特性只留调用点。
- **其余内置工具接入注册表**：既有缺口，独立立项。
- **IM 专用渠道实现**：扩展阶段。
- **重试与降级**：源文档未要求，且核心阶段禁止 fallback/hedge racing，不做。
