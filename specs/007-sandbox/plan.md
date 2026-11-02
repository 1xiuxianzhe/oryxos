# Implementation Plan: Sandbox 统一抽象与白名单校验

**Branch**: `007-sandbox` | **Date**: 2026-10-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-sandbox/spec.md`

## Summary

`005` 已经把白名单校验做成了可用的三方法校验器，但上层依赖的是"这一档实现"而不是"一道墙"。
本特性只做四件事：

1. **定死墙**：新增 `Sandbox` 接口 + `SandboxAction` + `ActionType`（+ 既有 `SandboxViolationException`）。
2. **迁移实现**：把 `SandboxChecker` 的全部行为迁进唯一实现 `WhitelistSandbox`，三个校验方法降为 `private`，
   对外只剩 `enforce(SandboxAction)`；删除 `SandboxChecker`，不留第二条调用路径。
3. **接线四个 Tool**：`FileTool` / `ShellTool` / `HttpTool` / `NotifyTool` 改为依赖 `Sandbox`，
   在副作用之前 `enforce`；`notify` 因目标 URL 来自 Profile，enforce 位置在"解析之后、发送之前"。
4. **把占位状态收口**：`Sandbox` 变成强制依赖（不再有"未装配校验器就放行"的 WARN 分支）；
   `005` 交付的 `oryxos.sandbox.enabled` 运维开关保留（默认 `true`、关闭告警、格式校验始终执行）。

**不做什么**：不写容器 / microVM 实现，不改 `ToolExecutor` 审计逻辑，不新增模块与依赖，
不动 Profile / ReAct / Memory / Web 任何代码。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**: 无新增。仅用 JDK（`java.nio.file`、`java.util.regex`）+ 既有 Spring Boot 配置绑定

**Storage**: 无变更（沙箱拒绝沿用既有 `tool_invocations` 审计写入）

**Testing**: JUnit 5 + Mockito + AssertJ（`mvn test`）；本地假 HTTP 服务复用 `FakeWebhookServer`

**Target Platform**: JDK 21；开发与运行环境为 Windows（命令工具走 `cmd.exe`，影响命令维度语义，见 A-006）

**Project Type**: Maven 多模块单体（后端）

**Constraints**:

- 只改 `oryxos-tool`（宪法 IV）；`oryxos-boot` 的 `application.yaml` 仅补注释，不改键名
- 不引入 Spring AI 的自动 tool 执行（宪法 III）
- 不使用 `SecurityManager`（宪法约束 4）
- 不引入核心阶段禁用能力：无并行 tool、无 SSE、无认证

**Scale/Scope**: 触及 1 个模块（`oryxos-tool`）+ 1 份配置注释；新增生产类 3 个、迁移 1 个、删除 1 个；
改造 4 个 Tool、2 个装配类；新增/改写 6 个测试类。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 / 约束 | 判定 | 说明 |
|---|---|---|---|
| I | Java 21 + Spring Boot 3.x 单体 | ✅ 通过 | 不新增模块、不改技术栈 |
| II | 自实现 ReAct 循环 | ✅ 通过 | 不触碰 `ReActLoop` |
| III | Spring AI 边界控制 | ✅ 通过 | 明确拒绝源文档的 `@Tool` 注解式接入（见 C-3） |
| IV | Tool 体系统一管理 | ✅ 通过 | 沙箱抽象与四个 Tool 全部落在 `oryxos-tool` |
| V | Memory 三层统一门面 | ✅ 通过 | 不触碰 Memory |
| VI | 审计表 day one 落库 | ✅ 通过 | 拒绝走既有工具失败路径，`tool_invocations` 照常写入，不新增审计分支 |
| VII | 核心阶段范围控制 | ✅ 通过 | 不做容器/microVM/并行/SSE；仅做应用层校验 |
| 约束 4 | Sandbox 用 Path/Pattern 白名单 | ✅ 通过 | 沿用并只保留这一档实现，无 `SecurityManager` |
| 约束 5 | SQLite 表结构手动维护 | ✅ 通过 | 无表结构变更 |

## Project Structure

### Documentation (this feature)

```text
specs/007-sandbox/
├── spec.md              # 规格（含冲突收敛结论 C-1 ~ C-7）
├── plan.md              # 本文件
├── research.md          # Phase 0：备选方案与取舍
├── data-model.md        # Phase 1：类型、状态与配置模型
├── quickstart.md        # Phase 1：怎么配、怎么验
├── contracts/
│   └── sandbox.md       # Phase 1：接口契约 + 验证项清单
├── checklists/
│   └── requirements.md  # 规格质量自检
└── tasks.md             # Phase 2：任务拆解与完成状态
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/oryxos/tool/
├── sandbox/
│   ├── Sandbox.java                    # 新增：唯一的墙
│   ├── SandboxAction.java              # 新增：动作值对象（type + target）
│   ├── ActionType.java                 # 新增：FILE_ACCESS / SHELL_EXEC / HTTP_REQUEST
│   ├── SandboxViolationException.java  # 已有（不改）
│   ├── WhitelistSandbox.java           # 新增：唯一实现（迁入 SandboxChecker 全部逻辑）
│   ├── SandboxChecker.java             # 删除（不留第二条调用路径）
│   └── SandboxProperties.java          # 已有（不改：单一前缀 oryxos.sandbox）
├── builtin/
│   ├── FileTool.java                   # 改：依赖 Sandbox，execute 首行 enforce(FILE_ACCESS)
│   ├── ShellTool.java                  # 改：依赖 Sandbox，execute 首行 enforce(SHELL_EXEC)
│   ├── HttpTool.java                   # 改：依赖 Sandbox，execute 首行 enforce(HTTP_REQUEST)
│   └── NotifyTool.java                 # 改：依赖 Sandbox（强制），解析目标后、发送前 enforce
└── config/
    ├── ToolAutoConfiguration.java      # 改：产出 Sandbox Bean（WhitelistSandbox），登记器依赖抽象
    └── NotifyAutoConfiguration.java    # 改：直接注入 Sandbox（去掉 ObjectProvider 可空注入）

oryxos-tool/src/test/java/io/oryxos/tool/
├── sandbox/WhitelistSandboxTest.java   # 新增（由 SandboxCheckerTest 改写 + 扩充绕过用例）
├── builtin/FileToolTest.java           # 改：构造 WhitelistSandbox
├── builtin/ShellToolTest.java          # 改：同上
├── builtin/HttpToolTest.java           # 改：同上
├── builtin/NotifyToolTest.java         # 改：mock Sandbox，校验 enforce 参数与顺序
├── config/ToolAutoConfigurationTest.java  # 改：Bean 类型与断言随抽象调整
└── config/NotifyAutoConfigurationTest.java # 改：上下文补 Sandbox Bean，去掉"未装配"用例

oryxos-boot/src/main/resources/application.yaml  # 改：注释对齐"统一沙箱 + notify 同受约束"
```

**Structure Decision**: 全部落在 `oryxos-tool`。沙箱是工具执行的前置环节，与 Tool 同属一个模块
（宪法 IV：Tool 相关代码不拆模块），且不向 `oryxos-core` 引入新概念。

## 实现决策记录

| # | 决策 | 理由 / 代价 |
|---|---|---|
| **D-01** | 新增 `Sandbox` 抽象，**删除** `SandboxChecker`（逻辑迁入 `WhitelistSandbox`，方法转 `private`） | 只删方法可见性不删类，等于把"绕过墙"的路留着；两个名字表示同一件事，后来者一定会走错。代价：测试类需迁移（用例保留并扩充） |
| **D-02** | 校验逻辑**迁移**而非重写：真实路径解析、通配转正则、`enabled` 开关、空白条目检查逐条保留 | 这些行为是 `005` 交付后被实测修正过的（符号链接绕过、开关纪律），重写等于把踩过的坑再踩一次 |
| **D-03** | 配置保留单一前缀 `oryxos.sandbox`，不用文档的三段式 | 见 spec C-2：避免顶层命名污染 + 破坏性配置变更 |
| **D-04** | Tool 一律 `execute` 首行 `enforce`；`notify` 在目标解析后、发送前 | 首行的位置本身就是"校验早于副作用"的结构性保证；`notify` 的 URL 来自 Profile，首行只能校验空值（假安全） |
| **D-05** | `Sandbox` 为**强制依赖**：`NotifyTool` 不再接受 `null`，装配处直接注入 | 消灭"未装配即放行"的状态；"强制启用"的准确含义是"不存在没有沙箱的运行期"，而非取消运维开关（D-06） |
| **D-06** | 保留 `oryxos.sandbox.enabled`（默认 `true`、关闭告警、格式校验始终执行） | 该开关是用户明确要求的能力；删掉是破坏性回退。两种状态必须区分：**没有沙箱**（本轮消灭）与**沙箱被显式关闭**（保留且有告警） |
| **D-07** | 命令首 token **精确匹配**（大小写敏感） | fail-closed：放宽大小写等于扩大白名单。代价：`ECHO` 被拒，已在 quickstart 写明 |
| **D-08** | 域名通配维持"转正则 + 点号转义"实现，不用 `endsWith` | `endsWith` 会让 `evil-example.com` 命中 `*.example.com`（文档点名的经典漏洞）；现有正则实现已天然带标签边界 |
| **D-09** | 接入回归测试断言**副作用为零**（文件未创建 / 目录探针未建 / 假服务零命中 / 适配器零调用），不只断言"抛了异常" | 只断言异常无法区分"先校验后执行"与"执行完才发现不允许"；副作用探针才是顺序证据 |
| **D-10** | 不新写审计代码 | 拒绝就是普通工具失败，`ToolExecutor` 既有路径已覆盖（宪法 VI） |

## Complexity Tracking

> 仅记录需要论证的"偏离"，其余均为直接沿用既有设计。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 删除既有类 `SandboxChecker`（属破坏性改动） | 抽象要成为唯一入口，就必须没有第二条等价路径；保留公开三方法等于墙没砌 | 只给 `SandboxChecker` 加 `implements Sandbox` 而不删方法：上层仍可绕过动作语义，接口中立性也为 0——`checkFilePath` 这类方法名正是 23 节点名要避免的"被第一档带偏" |
| 命令维度大小写敏感（与 Windows 实际执行语义不完全一致） | fail-closed：放宽匹配是"扩大白名单"，方向错误 | 大小写不敏感更贴近 `cmd.exe`，但会让"配置一个名字 = 允许一族写法"，且无法在 Linux 上保证语义一致 |
| `notify` 的 enforce 不在 `execute` 首行 | 首行拿不到 URL（来自 Profile 的目标解析），只能校验空值 | 把 URL 作为工具入参可以让位置前移，但那会改变 `notify` 的既有契约（渠道由 Profile 决定），属功能变更而非安全加固 |

## Phase 0 / Phase 1 输出

- Phase 0 研究结论见 [research.md](./research.md)
- Phase 1 类型与配置模型见 [data-model.md](./data-model.md)
- Phase 1 接口契约见 [contracts/sandbox.md](./contracts/sandbox.md)
- Phase 1 验收与使用指引见 [quickstart.md](./quickstart.md)
