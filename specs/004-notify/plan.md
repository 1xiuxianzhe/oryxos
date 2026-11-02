# Implementation Plan: Notify 出站通知模块

**Branch**: `004-notify` | **Date**: 2026-10-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-notify/spec.md`

**Source document**: `docs/class/第19节：Notify 模块 原理解析、实现与代码讲解.md`

**Related design artifacts**: [research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/notify.md](./contracts/notify.md)、[quickstart.md](./quickstart.md)

## Summary

入站有 Channel 负责"消息怎么进来"，本特性补上对称的另一半——"结果怎么主动送出去"。交付四件事：

1. **接口先行**：一个不携带任何渠道特有词汇的通知发送抽象 + 一个描述"通知目标"的数据载体。新增渠道只加实现类，不改接口、不改调用方。
2. **core 阶段唯一实现**：通用 webhook 推送（HTTP POST JSON），一次覆盖企业微信/飞书/钉钉群机器人这类 webhook 地址。
3. **`notify` 内置工具**：LLM 可调用，从当前处理上下文解析渠道、先做安全校验再发送、失败明确暴露。
4. **配置与脱敏**：Profile 的既有 `notify_channels` 字段开始被真正消费；通知地址属敏感信息，展示与错误信息中一律脱敏。

**与源文档的关键差异**（详见 spec.md 的 Assumptions 与本文「实现决策记录」）：源文档是教学示意代码，用了 `ProfileContext`、Spring AI `@Tool`、尚不存在的 `Sandbox.enforce`、Spring `RestClient` 与 MockWebServer；仓库实际是 `AgentContext`、`OryxTool`、`SandboxChecker`、JDK `HttpClient`。本计划**以仓库实际架构为准**，并把每处取舍写成可复核的决策。

## Technical Context

**Language/Version**: Java 21，`maven.compiler.release=21`

**Primary Dependencies**: 复用既有栈——`oryxos-core`（`OryxTool`/`ToolResult`/`ToolRegistry`/`AgentContext`/`Profile`）、Spring Context、JDK `java.net.http.HttpClient`、Jackson（测试断言用 `spring-boot-starter-test`）。**本特性不引入任何新的第三方运行期依赖**；测试侧也只用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 起本地假 webhook。

**Storage**: N/A。本特性不新增数据表、不新增文件。工具调用审计由既有 `ToolExecutor` 写 `tool_invocations`，本特性不绕过它，因而自动获得审计。

**Testing**: JUnit 5 + Mockito/AssertJ（`spring-boot-starter-test`，test scope）。`oryxos-tool` 模块**当前没有 `src/test` 目录、没有测试依赖**，需先补齐。默认测试离线、不需要 API key。

**Target Platform**: 与主工程一致（JDK 21 单二进制）。出站 HTTP 在 Windows/Linux/macOS 行为一致（JDK `HttpClient`）。

**Project Type**: Maven 9 模块单体应用中的一个支撑能力，落在既有 `oryxos-tool` 模块内，**不新增模块**。

**Performance Goals**: 单次通知的自身开销（不含目标 webhook 网络往返）< 10ms；连接超时 10s、请求超时 30s（与既有 `HttpTool` 取值一致，避免同类工具行为漂移）。

**Constraints**:

- 必须遵守 Constitution：Tool 相关全部归 `oryxos-tool`；不使用 Spring AI 的自动 tool 执行；不引入核心阶段禁止能力。
- `NotifyChannelAdapter` 的签名不得出现渠道特有词汇（接口中立性，SC-002）。
- 校验必须先于发送（SC-004）；失败不得静默（SC-003）。
- 通知地址与内嵌凭证不得出现在展示输出与错误信息中（SC-006）。
- 不新增 Maven 模块、不新增第三方运行期依赖。

**Scale/Scope**: 7 个生产类（1 接口 / 1 数据载体 / 1 实现 / 1 解析器 / 2 异常 / 1 脱敏工具工厂）+ 1 个工具 + 1 个装配配置、4 个测试类；覆盖"推送成功、渠道解析、缺省渠道、未配置报错、校验先于发送、脱敏"六类行为。**不含**：定时触发、多渠道管理、IM SDK 认证、统一 Sandbox 设计、把其余内置工具接入注册表。

## Constitution Check

*GATE: Must pass before implementation. Re-check after design and before completion.*

| # | 原则 | 本计划如何满足 | 结论 |
|---|---|---|---|
| I | Java 21 + Spring Boot 3.x 单体、Maven 9 模块、单二进制 | 只在既有 `oryxos-tool` 内加类，不新增模块与运行期依赖 | ✅ |
| II | 自实现 ReAct 循环 | 本特性不触碰 ReActLoop；`notify` 只是被 ReAct 调用的一个工具 | ✅ |
| III | Spring AI 边界控制（禁用自动 tool 执行） | **不使用** Spring AI 的 `@Tool` 注解与自动执行；统一走 `OryxTool` + `ToolExecutor` 显式调度 | ✅ |
| IV | Tool 体系统一管理 | 全部新增类落在 `oryxos-tool`（`io.oryxos.tool.notify` 与 `io.oryxos.tool.builtin`）；不碰 `ContextLoader` 的 SKILL.md 职责 | ✅ |
| V | Memory 三层记忆统一门面 | 本特性不涉及 Memory | ✅ |
| VI | SQLite 审计表 day one 落库 | 不绕过 `ToolExecutor`，`notify` 的调用自动写入既有 `tool_invocations` | ✅ |
| VII | 核心阶段范围控制 | 只做出站通知；不做定时触发、多渠道管理、IM SDK 认证、并行推送、重试编排 | ✅ |

**Gate result**: 通过，无需豁免；Complexity Tracking 留空。

## 实现决策记录

以下决策是"源文档 vs 仓库实际"差异的落点，逐条给出取舍与理由。

### D-01 抽象落点：`io.oryxos.tool.notify` 包，不新建模块

Constitution 原则 IV 要求 Tool 相关合并到 `oryxos-tool`，因此接口、数据载体、实现、解析器都放 `oryxos-tool`；工具类按既有习惯放 `io.oryxos.tool.builtin`。

### D-02 渠道目标数据载体用 `record`，且与 `Profile.NotifyChannelConfig` 分离

`NotifyTarget(channelType, config)` 是**对外抽象**的数据载体，刻意与 `Profile.NotifyChannelConfig`（`type` + `Map<String,Object> config`）分开：

- 前者面向"发送"，配置值统一收敛为 `String`（HTTP 场景只需要字符串）；
- 后者面向"配置解析"，`config` 是 `Map<String,Object>`（YAML 反序列化需要）。

**理由**：若直接复用配置类，抽象就被配置模型绑死——将来加一档实现需要不同值类型时，要么改配置模型、要么在发送侧做类型判断，两者都违反"接口先行"。转换由解析器一处完成。

### D-03 HTTP 客户端用 JDK `HttpClient`

源文档用 Spring `RestClient`。改用 JDK `HttpClient` 的三条理由：

1. 仓库既有 `HttpTool` 用的就是它，同类工具行为一致；
2. `oryxos-tool` 的类路径上未必有 `spring-web`（它只依赖 `oryxos-core` + `spring-ai-commons` + `mcp`），用 `RestClient` 要新增依赖；
3. 这是本特性唯一的出站调用点，用标准库即可，不值得为它拉入 Spring Web 栈。

### D-04 安全校验：复用 `SandboxChecker`，做成**可选**依赖

源文档用 `Sandbox.enforce(SandboxAction)`，该类型不存在。仓库真实等价物是 `SandboxChecker.checkHttpUrl(url)`，但其语义有个陷阱：

```java
boolean allowed = allowedDomains.stream().anyMatch(p -> p.matcher(host).matches());
```

**白名单为空时 `anyMatch` 恒为 false，即"全拒"**。若把 `SandboxChecker` 做成必需依赖而不提供配置来源，开箱即用的 `notify` 会 100% 失败。

**取舍**：`NotifyTool` 通过 `ObjectProvider<SandboxChecker>` 接收校验器——

- 装配了 → 发送前 `checkHttpUrl(url)`，顺序可测（满足 US3 场景 1、2）；
- 未装配 → 记一条 WARN（"出站白名单未生效"）后放行（满足 US3 场景 3）。

**理由**：源文档明确说"安全校验先占位，具体怎么做留给 Sandbox 那节"。真正的 Sandbox 特性要解决的是"三类白名单 + 配置来源 + 强制启用"，那是 23/24 节的范畴。本特性只保证**调用点存在且位于发送之前**，与仓库既有 `ToolInvocationValidator` + `NoopToolInvocationValidator` 的"seam 先行"模式一致。

**残留风险（显式记录）**：在 Sandbox 特性落地前，白名单实际未生效，WARN 日志是唯一的提示。这是"占位"的已知代价，不隐藏。

### D-05 渠道解析放在工具模块，不放进 `AgentContext`

源文档把 `resolveNotifyChannel` 放在 `ProfileContext`。仓库的 `AgentContext` 在 `oryxos-core`，而 `NotifyTarget` 在 `oryxos-tool`——若在 core 上开这个口子，core 就必须依赖 tool 的类型，**反向依赖，违反模块边界**。

**取舍**：新增 `NotifyTargetResolver`（`oryxos-tool`），输入 `Profile` + 可选渠道名，输出 `NotifyTarget`。`NotifyTool` 自己从 `AgentContext.current()` 取当前 Profile 再交给解析器，依赖方向保持 tool → core。

### D-06 测试假 webhook 用 JDK `HttpServer`，不引入 MockWebServer

源文档要求 MockWebServer。引入它会连带 okhttp + kotlin-stdlib，为单个测试点增加可观依赖。

**取舍**：用 JDK 自带的 `com.sun.net.httpserver.HttpServer` 在本地随机端口起假 webhook。**同样满足**源文档的验收意图：本地、离线、单测层、能断言请求体与 URL 来源、能构造 5xx。

### D-07 工具注册：在 `oryxos-tool` 新增装配配置，只注册 `notify`

`ToolRegistry` 只有手动 `register()`，**没有 Spring 自动收集**。当前启动时注册表为空，`FileTool`/`ShellTool`/`HttpTool` 均未注册（既有缺口）。

**取舍**：在 `oryxos-tool` 新增 `@Configuration`，构造 `WebhookNotifyAdapter` 与 `NotifyTool` 并注册进 `ToolRegistry`。

- 只注册 `notify` 一个：这是净增能力、无回归（原来一个都没有）；
- 把其余三个内置工具一并接入**不做**，避免范围蔓延——那是独立缺口，单独立项更合适。

### D-08 脱敏：把 `url` 纳入工具模块无关的展示层脱敏规则

webhook 地址常内嵌凭证（企业微信形如 `.../hook?key=<密钥>`）。既有 `profile show` 的脱敏是按关键字过滤行的（`key`/`secret`/`token`/`password`/`prompt` 等）——**含 `key=` 的地址会被命中，但形如 `https://host/hook/abc123` 的不会**。

**取舍**：把 `url` 加入脱敏关键字，使任何 URL 行都不再原样输出。

**代价**：provider 的 `base-url` 也会被遮挡。接受——`base-url` 对排查问题无足轻重，而漏掉一个 webhook 地址等于泄漏群组推送权限。

## Project Structure

### Documentation (this feature)

```text
specs/004-notify/
├── spec.md
├── plan.md              # 本文件
├── research.md          # 决策依据与替代方案对比
├── data-model.md        # NotifyTarget / 渠道配置 / 解析规则
├── quickstart.md        # 配置示例、运行与验收步骤
├── contracts/
│   └── notify.md        # 接口契约、工具契约、错误与脱敏契约
├── checklists/
│   └── requirements.md  # 规格质量自查
└── tasks.md             # 由 /speckit.tasks 生成
```

### Source Code (repository root)

```text
oryxos-tool/
├── pom.xml                                     # 新增 test scope 的 spring-boot-starter-test
├── src/main/java/io/oryxos/tool/
│   ├── notify/
│   │   ├── NotifyChannelAdapter.java           # 接口：send(NotifyTarget, String)
│   │   ├── NotifyTarget.java                   # record(channelType, config)
│   │   ├── WebhookNotifyAdapter.java           # 唯一实现：POST JSON
│   │   ├── NotifyTargetResolver.java           # Profile + 渠道名 -> NotifyTarget
│   │   ├── NotifyConfigException.java          # 渠道配置错误（未配置/不存在/类型不支持/缺 url/url 非法）
│   │   ├── NotifySendException.java            # 发送失败，携带 retryable（实现期补充）
│   │   └── NotifyUrls.java                     # 脱敏与 host 提取的唯一实现（实现期补充）
│   ├── builtin/
│   │   └── NotifyTool.java                     # implements OryxTool，name = notify
│   ├── config/
│   │   └── NotifyAutoConfiguration.java        # 装配 adapter + tool + 注册进 ToolRegistry
│   └── sandbox/                                # 既有：SandboxChecker（本特性复用，不修改）
└── src/test/java/io/oryxos/tool/
    ├── notify/
    │   ├── WebhookNotifyAdapterTest.java       # 本地假 webhook
    │   └── NotifyTargetResolverTest.java       # 渠道解析与错误分支
    ├── builtin/
    │   └── NotifyToolTest.java                 # 校验先于发送、失败语义
    └── support/
        └── FakeWebhookServer.java              # JDK HttpServer 封装的测试脚手架

oryxos-core/                                    # 只读复用，不修改
├── tool/OryxTool.java、ToolResult.java、ToolRegistry.java
├── agent/AgentContext.java
└── profile/Profile.java（含既有 NotifyChannelConfig）

oryxos-web/                                     # 仅同步硬编码的工具清单
└── src/main/java/io/oryxos/web/controller/ToolApiController.java

oryxos-cli/                                     # 可选：默认 Profile 模板补注释示例
└── src/main/java/io/oryxos/cli/ProfileTemplates.java
```

**Structure Decision**: 维持 9 模块结构。渠道抽象与实现、解析器、工具装配全部落在既有 `oryxos-tool`（Constitution IV）；`oryxos-core` 只提供既有类型，**不修改**；`oryxos-web` 只做清单一致性同步；不新增任何模块。

## Key Interfaces and Flow

```text
LLM 决定调用 notify
        │
        ▼
ToolExecutor（既有：写 tool_invocations 审计 → 前置校验 → 执行）
        │
        ▼
NotifyTool.execute({content, channel?})
        │
        ├─ 1. AgentContext.current()            -> 当前 Profile（core）
        ├─ 2. NotifyTargetResolver.resolve(...)  -> NotifyTarget
        │        ├─ 未配置 notify_channels      -> NotifyConfigException
        │        ├─ 指定渠道不存在              -> NotifyConfigException
        │        ├─ type 不受支持               -> NotifyConfigException
        │        ├─ config 缺 url / url 非法     -> NotifyConfigException
        │        └─ channel 缺省 -> 取第一个
        ├─ 3. NotifyGuard（可选 SandboxChecker）
        │        ├─ 已装配 -> checkHttpUrl(url)  ← 必须早于第 4 步
        │        └─ 未装配 -> WARN「出站白名单未生效」
        └─ 4. NotifyChannelAdapter.send(target, content)
                 └─ WebhookNotifyAdapter：POST {"content": ...}
        │
        ▼
ToolResult.ok("已推送") / ToolResult.fail(原因)
```

顺序不变式：**第 3 步永远早于第 4 步**；第 2、3 步任一失败时第 4 步调用次数为 0。

## Constitution Re-check After Design

- 新增类全部在 `oryxos-tool`，未新增模块、未新增运行期依赖 ✅
- 未使用 Spring AI 的自动 tool 执行与 `@Tool` 注解，工具仍由 `ToolExecutor` 显式调度 ✅
- core 未被反向依赖：渠道解析器在 tool 模块，core 只提供既有类型 ✅
- 工具调用审计沿用既有路径，未绕开 `tool_invocations` ✅
- 未引入核心阶段禁止能力（无定时、无并行推送、无重试编排、无多渠道管理）✅
- 出站请求有校验调用点且位于发送之前；未装配校验器时以 WARN 显式暴露，不静默 ✅

**Result**: 通过，可进入 `/speckit.tasks`。

## Complexity Tracking

无宪法违规项，本表留空。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| — | — | — |
