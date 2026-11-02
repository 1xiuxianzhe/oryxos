# Implementation Plan: Tool 体系

**Branch**: `005-tool-system` | **Date**: 2026-10-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-tool-system/spec.md`

## Summary

工具体系的**抽象层已经完整且真实可用**（工具接口、执行结果、注册表、执行器、前置校验与审计钩子），
六个内置工具的**实现类也真实存在**。本特性不重写任何抽象，只做五件"把能力接上线"的事：

1. **注册装配**：把 `read_file` / `write_file` / `list_dir` / `shell` / `http_get` / `http_post`
   六个内置工具注册进共享注册表（当前运行时只有 `notify` 一个）。
2. **白名单装配与配置来源**：新增白名单校验器的装配与配置绑定，使校验链路真正生效
   （当前校验器没有任何生产者，恒为"未装配"）。
3. **按 Agent 授权**：让 Agent 配置里声明的工具清单真正参与过滤（当前该字段无任何消费方）。
4. **外部工具服务接入（MCP）**：新增客户端装配，按工作区声明连接外部服务、包装注册其工具、
   失联隔离、调用转发（当前完全缺失，只有依赖坐标与一个空 YAML 模板）。
5. **清单真实化**：命令行与 HTTP 两个入口改为读真实注册表（当前一个列目录、一个硬编码）。

**关键约束（决定任务不可拆分）**：内置工具**强依赖非空的白名单校验器**，缺失即构造失败；
且校验器为空清单时"全拒"。因此第 1 件与第 2 件必须同时交付，`spec.md` 的 `A-003` 已记录。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**: Spring Boot 3.3.5、SnakeYAML（Profile YAML）、Jackson、
Spring Data JPA + SQLite、Picocli、`io.modelcontextprotocol.sdk:mcp:0.8.1`（已在依赖管理中，首次被真正使用）

**Storage**: SQLite（`tool_invocations` 审计表已存在，本特性只保证新工具同样写入）

**Testing**: JUnit 5 + Mockito + AssertJ（`mvn test`，surefire）；假外部服务用 JDK `HttpServer`，
**不引入 MockWebServer / WireMock**

**Target Platform**: JDK 21 运行时；开发环境为 Windows（影响命令工具的平台假设，见 A-007）；
交付物为单二进制 fat JAR

**Project Type**: Maven 多模块单应用（后端）

**Constraints**:

- 不新增 Maven 模块（宪法「模块边界约束」）
- 不引入新的第三方运行期依赖（MCP 复用既有坐标）
- 不引入 Spring AI 的自动 Tool 执行（宪法 III）
- 所有工具相关代码落在 `oryxos-tool`（宪法 IV）

**Scale/Scope**: 本特性触及 5 个模块（`oryxos-core`、`oryxos-tool`、`oryxos-boot`、`oryxos-cli`、
`oryxos-web`），新增/修改生产类约 20 个，新增测试类约 8 个。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 / 约束 | 判定 | 说明 |
|---|---|---|---|
| I | Java 21 + Spring Boot 3.x 单体 | ✅ 通过 | 不新增模块、不改技术栈 |
| II | 自实现 ReAct 循环 | ✅ 通过 | 不触碰 ReActLoop 的循环逻辑，只在工具解析点加过滤 |
| III | Spring AI 边界控制（禁用自动 Tool 执行） | ✅ 通过 | **本特性明确不引入注解式自动注册**，沿用"手写实现统一接口 + 装配处显式登记"，见 D-01 |
| IV | Tool 体系统一管理 | ✅ 通过 | 内置工具、外部服务客户端与适配器、白名单校验全部落在 `oryxos-tool`；SKILL.md 加载继续由 core 的 ContextLoader 负责 |
| V | Memory 三层统一门面 | ✅ 通过 | 不触碰 Memory；两个记忆工具**不在本特性范围**（A-006） |
| VI | 审计表 day one 落库 | ✅ 通过 | 沿用既有执行器审计钩子，新注册的工具自动覆盖；外部工具调用同样经执行器，不绕过 |
| VII | 核心阶段范围控制 | ⚠️ 需说明 | 见 Complexity Tracking：外部服务接入使用 SSE 作为**协议内部传输**，与"对外流式响应 SSE"不是同一件事 |
| 约束 4 | Sandbox 用 Path/Pattern 白名单 | ✅ 通过 | 完全沿用既有白名单实现，不引入 SecurityManager |
| 约束 5 | SQLite 表结构手动维护 | ✅ 通过 | 不新增表、不改表结构 |

**开发时检查清单**：7 条原则逐条对照无违反；Spring AI 自动执行未被引入；Provider 显式映射未触碰；
工具代码全在 `oryxos-tool`；SKILL.md 加载仍在 ContextLoader；Memory 门面未触碰；
审计落库路径未绕过；未引入核心阶段禁用的能力。

## Project Structure

### Documentation (this feature)

```text
specs/005-tool-system/
├── spec.md              # 规格（已完成并通过评审）
├── plan.md              # 本文件
├── research.md          # Phase 0：技术选型与决策记录
├── data-model.md        # Phase 1：实体与配置模型
├── quickstart.md        # Phase 1：怎么配、怎么用、怎么验
├── contracts/           # Phase 1：接口契约
│   ├── tool-registry.md
│   ├── tool-authorization.md
│   ├── tool-invocation.md
│   └── mcp-client.md
└── tasks.md             # Phase 2 输出（由 /speckit.tasks 生成）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── tool/
│   ├── OryxTool.java                      # 已有：工具统一抽象（不改）
│   ├── ToolResult.java                    # 已有：执行结果（不改）
│   ├── ToolRegistry.java                  # 改：补 contains / 重名拒绝（FR-003）
│   ├── ToolInvocationValidator.java       # 已有：前置校验端口（不改）
│   └── NoopToolInvocationValidator.java   # 已有（不改）
├── react/
│   ├── ReActLoop.java                     # 改：可用工具由"全量"改为"按 Agent 解析"
│   └── ToolExecutor.java                  # 已有：执行链路与审计（不改）
├── profile/
│   └── Profile.java                       # 改：工具授权声明的读取语义（getTools 已存在）
└── tool/
    └── ToolResolver.java                  # 新增：按 Agent 授权解析可用工具子集

oryxos-tool/src/main/java/io/oryxos/tool/
├── builtin/                               # 已有实现（不改工具本体，只补注册）
│   ├── FileTool.java
│   ├── ShellTool.java
│   ├── HttpTool.java
│   └── NotifyTool.java
├── sandbox/
│   ├── SandboxChecker.java                # 已有（不改）
│   ├── SandboxViolationException.java     # 已有（不改）
│   └── SandboxProperties.java             # 新增：白名单配置绑定
├── mcp/
│   ├── McpServerDeclaration.java          # 新增：外部服务声明模型（stdio / 远程）
│   ├── McpServerConfigLoader.java         # 新增：读取工作区声明文件
│   ├── McpClientProvider.java             # 新增：建立连接（可注入以便测试）
│   ├── McpToolAdapter.java                # 新增：外部工具 → 统一抽象
│   └── McpClientService.java              # 新增：连接全部、包装注册、失联隔离、关闭
└── config/
    ├── NotifyAutoConfiguration.java       # 已有（不改）
    └── ToolAutoConfiguration.java          # 新增：白名单 + 内置工具注册 + MCP 装配

oryxos-cli/src/main/java/io/oryxos/cli/
└── ToolCommand.java                       # 改：读真实注册表（FR-023）

oryxos-web/src/main/java/io/oryxos/web/controller/
└── ToolApiController.java                 # 改：读真实注册表（FR-023 / FR-025）

oryxos-boot/src/main/resources/
└── application.yaml                       # 改：新增白名单与外部服务相关配置段
```

**Structure Decision**: 沿用既有的 Maven 多模块单体结构，不新增模块。
新增代码集中在 `oryxos-tool`（宪法 IV），只在与"装配/消费"直接相关处改动
`oryxos-core`（过滤点）、`oryxos-boot`（配置）、`oryxos-cli` 与 `oryxos-web`（清单入口）。

## 实现决策记录

沿用本仓库惯例（`004-notify` 的 plan.md 亦设此节），逐条记录取舍与理由。

| # | 决策 | 理由 / 代价 |
|---|---|---|
| **D-01** | **不引入注解式自动注册**，内置工具沿用"手写实现统一抽象 + 在装配处显式登记" | 宪法 III 禁用 Spring AI 自动执行；既有 `notify` 已用此方式，保持一致。代价：新增工具需在装配处加一行，靠契约测试兜住"漏注册" |
| **D-02** | **白名单校验器新增装配与配置绑定**，配置项落在 `application.yaml`；**空清单 = 全拒** | 内置工具强依赖非空校验器，不装配就构造不出来；默认收紧是安全边界的正确方向（A-005）。代价：新建工作区不配白名单时工具全不可用，故失败原因必须可读 |
| **D-03** | 命令工具**保持现有的平台行为**（Windows 命令解释器），本特性不改 | 属既有独立缺口，改动会扩大回归面且无法在纯单测里验证跨平台；在文档中显式标注平台假设（A-007） |
| **D-04** | 工具授权过滤落在 **ReAct 组装可用工具的那一个点**，以 Agent 配置为粒度；**未声明或为空 = 不限制** | 过滤点只有一个，避免漏改；粒度按 Agent 而非全局，否则多 Agent 场景会互相污染（FR-006）。空值语义见 A-004（已确认） |
| **D-05** | 授权声明的**判据是工具名**，沿用既有对象形式的声明结构，同时**容忍纯字符串写法** | 既有声明结构是对象列表且已有解析测试，不能破坏；纯字符串更符合手写习惯，作为兼容增强。若两种形式在解析层冲突，则保留对象形式并在文档中明确 |
| **D-06** | 外部工具**不重命名、不加前缀**，沿用外部服务报出的工具名；**重名拒绝注册并告警** | 保持"工具名全局唯一"这一既有不变式，且与源文档的预期一致。代价：外部服务与内置工具撞名时后者不可用，必须有可见告警 |
| **D-07** | **连接信息的唯一真相来源是工作区声明文件**；Agent 配置里的外部服务字段语义为"该 Agent 引用哪些服务" | 源文档明确"启动时连接所有配置的 MCP server"（全局），而 §五 又说"Profile 里引用要复用的"。两者拼起来正好是"全局声明 + 按 Agent 引用"，且不必破坏既有的配置模型与解析测试 |
| **D-08** | 外部服务**两种连接方式都支持**（本地子进程 / 远程） | A-009 明确要求；两种传输在 SDK 中均已提供 |
| **D-09** | 复用 **既有 MCP 依赖**，不引入新的第三方依赖 | A-010；本地仓库已有该版本产物，且提供客户端与两种传输。若能力不足再转为自实现协议转发 |
| **D-10** | **连接与注册发生在启动阶段**；单个服务失败只告警 | FR-020 / FR-022；外部依赖的可用性不等于自身的可用性 |
| **D-11** | 外部工具调用**经既有执行器链路**，不绕过 | 保证审计落库（FR-024）与失败语义统一（FR-021） |
| **D-12** | 清单入口**直接读注册表**，不引入缓存或第二份清单 | FR-023 / FR-025；"唯一真相来源"是不变式，缓存会立刻产生不一致 |
| **D-13** | 白名单校验**在工具内部调用点触发**（既有形态），不新增统一的动作对象层 | 统一沙箱抽象属后续 Sandbox 特性（A-008）；本次只保证"调用点存在且在副作用之前" |

## Complexity Tracking

> 仅记录需要论证的"偏离"，其余均为直接沿用既有设计。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 外部服务接入使用 **SSE** 作为传输 | 该协议客户端提供的远程传输就是 SSE；这是**协议内部传输**，与宪法 VII 所列"对外流式响应 SSE"（面向调用方的增量返回）不是同一件事——本特性对外的工具调用仍是同步阻塞的 | 只支持本地子进程传输可回避该项，但会拒绝掉 A-009 明确要求的"远程服务"场景，且 SDK 已提供远程传输，回避没有收益 |
| 修改 `oryxos-core` 的过滤点与注册表（而非只在工具模块内改动） | 过滤发生在 Agent 组装可用工具处，该处属 core；注册表需补"重名拒绝"以满足 FR-003 | 把过滤下推到工具模块会让 core 反向依赖工具模块，违反既有依赖方向（19 节已就同一问题做过取舍） |

## Phase 0 / Phase 1 输出

- Phase 0 研究结论见 [research.md](./research.md)
- Phase 1 实体与配置模型见 [data-model.md](./data-model.md)
- Phase 1 接口契约见 [contracts/](./contracts/)
- Phase 1 验收与使用指引见 [quickstart.md](./quickstart.md)
