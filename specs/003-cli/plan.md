# Implementation Plan: CLI 命令行入口与会话管理

**Branch**: `003-cli` | **Date**: 2026-09-28 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-cli/spec.md`

**Source document**: `docs/class/第18节：CLI 功能概述、实现思路与代码讲解.md`

**Related design artifacts**: [research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/cli.md](./contracts/cli.md)、[contracts/session-store.md](./contracts/session-store.md)、[quickstart.md](./quickstart.md)

## Summary

本特性补齐 OryxOS 的 CLI 入口和共享会话层。实现保留现有 12 个 Picocli 命令注册和 Core/Storage 分层，重点完成以下链路：

1. `init`、查询和 Profile 管理命令直接操作工作区文件或轻量存储，不启动完整 Agent 运行时。
2. `chat` 通过 `CliChannel` 读取 stdin、处理 `/quit`、调用统一 `AgentService` 并输出最终回复，不复制 ReAct、Provider、Tool 或 Memory 逻辑。
3. `SessionManager` 按 `channel + userId + profileName` 幂等获取 Session，`JpaSessionStore` 将消息历史保存到 SQLite，并支持重启恢复。
4. `chat`、`serve`、`gateway` 通过共享的重命令启动协调器加载完整运行能力，保证 Core、Provider、Web/Channel、Storage、JPA Repository 和 Entity 正确装配。
5. 所有命令提供可发现的帮助、明确的失败状态和脱敏错误；Profile 删除不删除已有 Session 和审计数据。

**实现状态（2026-10-09 更新）**：本计划已实施完成，5 条链路全部落地——轻命令不启 Spring、`chat` 委托统一 `AgentService`、会话按三元组幂等且跨重启恢复、三个运行模式共享同一 Spring 运行时、失败返回非零退出并脱敏。全量 `mvn test`（10 模块）通过，`chat` 多轮对话与重启恢复已用真实进程 + 真实模型手工验证。

实施过程中出现 4 项原计划未预见的工作，已补充为独立任务并完成，详见 `tasks.md` 的 Phase 8 与本文「实现结果与偏差记录」：进程入口分流（`OryxosLauncher`）、默认 Profile 模板修正、Windows 控制台编码、`oryxos` 命令包装脚本。

## Technical Context

**Language/Version**: Java 21，`maven.compiler.release=21`

**Primary Dependencies**: Spring Boot 3.x、Spring Context、Spring Data JPA、Picocli、Jackson、SnakeYAML、SQLite JDBC、Hibernate Community Dialects、JUnit 5、Mockito、AssertJ、SLF4J/Logback；不新增第三方依赖。

**Storage**: SQLite + Spring Data JPA，默认工作区数据库为 `.oryxos/oryxos.db`；Profile、Bootstrap、Skill、Memory 和日志使用 `.oryxos/` 文件系统；Session 历史保存于现有 `sessions.messages_json`。

**Testing**: JUnit 5 + Mockito/AssertJ + Spring Boot Test；临时工作区和测试 SQLite；默认测试不依赖真实网络或 API key；真实模型调用保留为手动集成验证。

**Target Platform**: Windows/Linux/macOS 上的 JDK 21 单二进制运行方式；本地 CLI 和服务端运行模式共用同一代码。

**Project Type**: Maven 9 模块单体应用，CLI/Channel/Storage 支撑模块接入既有 Core、Provider、Web、Boot。

**Performance Goals**: 轻命令常规本地环境目标不超过 1 秒且不等待完整运行时启动；CLI 自身不增加模型调用之外的显著处理开销；默认测试套件保持可重复且无网络依赖。

**Constraints**:

- 必须保持 JDK 21、Spring Boot 3.x、Maven 9 模块和单二进制边界。
- CLI 是薄入口；ReAct、Provider、Tool、Memory、审计和请求失败分类由既有服务负责。
- Session ID 与三元组查找只由 `SessionManager` 负责，入口不得自行拼接。
- Core 不得反向依赖 Storage；通过 `SessionStore` 端口连接 JPA 实现。
- SQLite 表结构演进使用手工 schema/migration，不依赖 `ddl-auto=update`。
- 不新增认证、IM Channel、SSE、WebSocket、并行 Tool、Agent 委托、向量检索或复杂编排。
- 错误不得静默吞掉；输出和日志不得泄露 API key、凭证或完整系统提示词。

**Scale/Scope**: 12 个核心 CLI 命令、1 个 CLI Channel、1 套共享 Session 管理和 SQLite 存储接线；覆盖初始化、查询、Profile 管理、聊天、三种运行模式的启动边界和自动化验收。不实现 Web 资源本身、外部 IM Channel 或完整 Gateway 路由。

## Constitution Check

*GATE: Must pass before implementation. Re-check after design and before completion.*

| # | 原则 | 本计划如何满足 | 结论 |
|---|---|---|---|
| I | JDK 21 + Spring Boot 3.x 单体、Maven 9 模块、单二进制 | 仅修改既有 `oryxos-core`、`oryxos-storage`、`oryxos-channel-cli`、`oryxos-cli`、必要的 `oryxos-boot`/`oryxos-web` 接线，不增加模块和依赖 | ✅ |
| II | 五大核心能力优先，核心阶段交付运行时内核 | CLI 只作为入口和调试通道；Web/Gateway 具体扩展能力不在本特性实现 | ✅ |
| III | 自实现 ReAct loop | CLI 只调用 `AgentService`，不直接实现或复制 ReAct 循环 | ✅ |
| IV | Spring AI 只用 Provider/协议/schema，禁用自动 Tool 执行 | CLI 不接入 Spring AI 自动 Tool 执行，所有处理继续经过既有 `AgentService`/`ReActLoop` | ✅ |
| V | Tool 统一归 `oryxos-tool`，SKILL.md 归 ContextLoader | CLI 只查询/展示 Tool 信息，不实现 Tool；不移动 Skill 加载职责 | ✅ |
| VI | Memory 通过 `MemoryService` 统一门面 | CLI 不直接读写会话记忆或长期记忆；聊天消息交给既有 Agent/Memory 编排 | ✅ |
| VII | 审计 day one 落库 | 重命令使用既有 Storage 审计装配；CLI 不绕过 AgentService，保证 Agent Run、LLM 和 Tool 审计路径可用 | ✅ |
| VIII | 核心阶段不做并行、压缩、委托、SSE、认证等 | 本特性只交付同步 CLI 交互、启动分流和 Session 持久化，后置能力明确排除 | ✅ |

**Gate result**: 通过，无需豁免；Complexity Tracking 留空。

## Implementation Phases

### Phase 1: Core Session identity and persistence contract

目标：先把所有入口共享的 Session 地基钉死。

- 扩展 Core `SessionStore` 端口，提供按 `channel + userId + profileName` 查找和安全列举所需的语义。
- 修改 `SessionManager`，实现内存索引优先、Store 查找其次、创建最后的 `getOrCreate` 流程。
- 保证 Session ID 生成集中于 `SessionManager`，三元组唯一性和不同维度隔离可验证。
- 扩展 `SessionRepository`/`JpaSessionStore` 的三元组查询、完整消息 JSON 读写和错误传播；保存异常不得只记录日志后返回成功。
- 更新 `sessions` schema/迁移脚本，补充活动 Session 的业务唯一约束或等价保护；不删除已有历史。
- 复用现有 `SessionEntity` 字段，不把消息拆成新表，不改变 Core → Storage 依赖方向。

**Exit criteria**: `SessionManagerTest` 和 `SessionRepositoryTest` 覆盖幂等、隔离、JSON 顺序、重启读回、删除 Profile 后历史保留、持久化失败可见。

### Phase 2: CLI Channel and heavy command wiring

目标：让 `chat` 成为真实 Agent 入口，并保证重命令完整装配。

- 修改 `CliChannel`：支持传入 Profile、稳定本地用户标识、空行/EOF、`/quit`；普通消息调用 `AgentService`，打印最终回复和脱敏错误。
- 修改 `ChatCommand`：接收 `--profile`，默认 `default`，从运行上下文取得 `SessionManager` 和 `AgentService`，不再固定构造内存 Session 或输出 echo。
- 新增共享重命令启动协调器或等价启动路径：只启动一次完整上下文，显式覆盖组件、JPA Repository、Entity 和必要的自动配置扫描范围。
- 保证重命令启动后能获得 ProfileRegistry、LlmClient、AgentService、SessionStore 和审计实现；缺少必需配置时启动前清晰失败。
- 将 `serve` 接到既有 Web 启动边界，将 `gateway` 接到统一运行服务/Channel 边界；不在 CLI 新建 Agent 逻辑。
- 保留 `/quit` 不提交给 Agent；Agent 失败、超时和持久化失败均返回非成功状态并记录原因。

**Exit criteria**: `CliChannel` 单测验证两轮消息、委托次数、Profile 选择、`/quit`、空行、EOF 和失败传播；重命令启动测试确认 JPA Repository/Entity 可用。

### Phase 3: Light commands and workspace/Profile operations

目标：完成无需完整运行时的 9 个轻命令及帮助/错误行为。

- 实现 `init`：创建 `.oryxos/`、Profile、Memory、Skill、Session、logs、Bootstrap、数据库和默认 Profile；重复执行不得覆盖已有文件。
- 实现 `status`：输出工作区和运行摘要；工作区缺失给出初始化诊断。
- 实现 `profile list/create/show/delete`：使用文件系统 Profile 约定，处理重复、不存在、非法名称和默认 Profile 保护；删除不级联历史数据。
- 实现 `provider list` 与 `tool list`：输出可识别名称或空结果，敏感字段只输出脱敏摘要。
- 实现 `session list`：读取安全摘要，避免输出完整系统提示词、凭证或超长敏感内容。
- 为根命令和每个子命令补充用途、参数、默认值和示例帮助；非法参数使用非成功状态。
- 确保轻命令不创建完整 Spring/Agent 上下文；其工作区位置解析与 `init` 一致。

**Exit criteria**: 轻命令行为测试和命令帮助测试覆盖 12 个命令注册、成功/空结果/失败场景，并验证不要求 API key。

### Phase 4: Integration verification and documentation alignment

目标：验证三个运行模式共享配置和 Session，并完成交付记录。

- 增加 CLI 模块端到端测试：初始化、Profile 管理、双轮聊天、Session 重启恢复、轻重命令分流、帮助信息和敏感信息脱敏。
- 使用假 Agent/假 Provider 验证默认测试无网络依赖；增加一个默认跳过的真实调用 smoke test，沿用项目现有 API key 配置方式。
- 根据 `quickstart.md` 手工确认 `chat`、`serve`、`gateway` 的启动日志和共享存储路径；验证失败启动不会留下半初始化状态。
- 同步 README 或交付说明中 CLI 实际支持范围；若 `serve/gateway` 的完整业务能力尚未就绪，明确标记为启动接线/后续能力而非虚假完成。
- 执行全量 Maven 测试、`/speckit.analyze`，修正 spec/plan/tasks 与实现的漂移。

**Exit criteria**: 自动化测试通过；12 个命令可发现；会话幂等、隔离、持久化和错误传播可复现；文档交付物与实际状态一致。

## Project Structure

### Documentation (this feature)

```text
specs/003-cli/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── cli.md
│   └── session-store.md
├── checklists/
│   └── requirements.md
└── tasks.md              # 由 /speckit.tasks 生成
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/session/
│   ├── Session.java                    # 消息/状态/时间戳 + restore()
│   ├── SessionManager.java             # getOrCreate 三元组幂等 + 内存索引 + 恢复
│   └── SessionStore.java               # 端口：findById / findByIdentity / save / list
└── src/test/java/io/oryxos/core/session/
    └── SessionManagerTest.java         # 幂等、隔离、按业务身份恢复

oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── entity/SessionEntity.java
│   ├── repository/SessionRepository.java   # 三元组查询 + 列表
│   └── session/JpaSessionStore.java        # JSON 往返、异常上抛
├── src/main/resources/db/
│   ├── schema.sql                     # 含 uq_sessions_active_identity 部分唯一索引
│   └── migration/                     # 既有 3 个审计表迁移；sessions 身份迁移见 T010
└── src/test/java/io/oryxos/storage/session/
    └── JpaSessionStoreTest.java       # 存读回、接续追加、不存在返回空

oryxos-channel-cli/
├── src/main/java/io/oryxos/channel/cli/CliChannel.java   # Console 交互 + /quit + 委托 AgentService
└── src/test/java/io/oryxos/channel/cli/CliChannelTest.java

oryxos-cli/
├── src/main/java/io/oryxos/cli/
│   ├── OryxOsCli.java        # Picocli 根命令 + init/status/chat/serve/gateway
│   ├── CommandMode.java      # LIGHT / HEAVY
│   ├── CommandModes.java     # argv → 运行模式判定
│   ├── RuntimeLauncher.java  # 重命令运行时端口
│   ├── WorkspaceLayout.java  # .oryxos 路径解析 + 初始化
│   ├── ProfileTemplates.java # 默认 Profile 模板
│   ├── ProfileCommand.java
│   ├── ProviderCommand.java
│   ├── ToolCommand.java
│   └── SessionCommand.java
└── src/test/java/io/oryxos/cli/
    ├── CommandHelpTest.java
    ├── CommandModesTest.java
    ├── InitCommandTest.java
    ├── ProfileCommandTest.java
    ├── QueryCommandTest.java
    └── RuntimeCommandTest.java       # 重命令运行时边界 + serve 非法端口

oryxos-boot/
├── src/main/java/io/oryxos/
│   ├── OryxosLauncher.java        # 进程入口：编码接管 + 轻重分流
│   └── OryxosApplication.java     # Spring 配置 + 显式 JPA 扫描
├── src/main/java/io/oryxos/boot/
│   ├── ConsoleEncoding.java       # 控制台宽字符接管（无控制台时 no-op）
│   └── SpringRuntimeLauncher.java # RuntimeLauncher 唯一实现
├── src/main/resources/application.yaml   # logging.charset.console=UTF-8
└── src/test/java/io/oryxos/boot/
    ├── ConsoleEncodingTest.java
    └── SpringRuntimeLauncherTest.java

bin/
└── oryxos.cmd                 # 运行包装脚本（纯 ASCII + CRLF，非 Maven 模块）

oryxos-web/
└── existing Web startup/API boundary reused by `serve`; no new REST surface in this feature
```

**Structure Decision**: 维持现有 9 模块结构。Session 抽象和业务身份逻辑在 `oryxos-core`；SQLite/JPA 适配和 schema 在 `oryxos-storage`；终端交互在 `oryxos-channel-cli`；Picocli 命令注册和轻重分流在 `oryxos-cli`；完整运行时装配在既有 Boot/Web 边界。不得把 Storage 实现搬进 CLI，也不得把 ReAct 逻辑搬进 Channel。

## Key Interfaces and Flow

进程级分流（实现后的真实结构）：

```text
java -jar oryxos-boot.jar <command> [options]
  │
  └─ OryxosLauncher.main
        ├─ ConsoleEncoding.installConsoleStreams()   # 有控制台才接管，否则 no-op
        │
        ├─ 非 CLI 调用 ──> SpringApplication.run(...)              # 保持既有 Web 启动行为
        │
        ├─ LIGHT  ────────> new OryxOsCli().execute(args)          # 不构造 Spring 上下文
        │
        └─ HEAVY  ────────> SpringApplication (SERVLET | NONE)
                              └─ getBean(RuntimeLauncher)
                                   └─ new OryxOsCli(launcher).execute(args)
```

命令与运行时边界：

```text
OryxOsCli
  ├─ light commands -> WorkspaceLayout / 工作区文件 -> stdout/stderr -> exit code
  └─ heavy commands -> RuntimeLauncher（由 SpringRuntimeLauncher 实现）
                           ├─ ProfileRegistry
                           ├─ AgentService
                           ├─ SessionManager -> SessionStore -> JpaSessionStore -> SQLite
                           ├─ Provider / LlmClient
                           ├─ Tool / Memory / Audit
                           └─ CliChannel（chat）或 Web/常驻边界（serve/gateway）
```

`chat` request flow：

```text
stdin line（Console.readLine / UTF-8 流）
  -> CliChannel.handleLine：判 /quit、跳空行、其余交给 AgentService
  -> AgentService.doProcess：runId + MDC + 请求级审计
  -> ProfileRegistry.getProfile(profile)
  -> ReActLoop.runResult：拼 prompt -> 调模型 -> 累积响应 -> 判工具
  -> SessionManager.save(session)（在 finally 中，成功/失败/超时都落库）
  -> stdout 最终回复
```

## Constitution Re-check After Design

- Core 仍只依赖 `SessionStore` 接口，Storage 通过适配实现持久化，未产生反向依赖 ✅
- `CliChannel` 只调用 `AgentService`，未引入第二套 ReAct 或 Provider 路径 ✅
- 12 个命令均属于既定 CLI 范围（5 顶层 + 4 个 `profile` 子命令 + 3 个列表命令）；`serve`/`gateway` 只复用既有边界，未扩展 IM、SSE 或认证 ✅
- Session 业务唯一性和审计写入都通过共享服务/存储完成，未在 CLI 里复制数据库逻辑 ✅
- 没有新增 Maven 模块；未新增第三方依赖（仅 `oryxos-cli`/`oryxos-channel-cli` 补了 test scope 的 `spring-boot-starter-test`）✅

**Result**: 通过。

## 实现结果与偏差记录

### 与原计划一致的部分

| 计划项 | 实际结果 |
|---|---|
| 轻命令不启 Spring | `CommandModes` 判定 + `OryxosLauncher` 分支，轻命令路径上不触碰 `SpringApplication` |
| `chat` 委托统一 Agent | `CliChannel` 只做渠道协议，ReAct/Provider/Tool/Memory/审计全部复用既有服务 |
| 会话按三元组幂等 | `SessionManager.getOrCreate(channel, userId, profileName)`，ID 生成只此一处 |
| 跨重启恢复 | `JpaSessionStore.findByIdentity` → `Session.restore()`，`messages_json` 整体序列化 |
| 持久化异常不吞 | 序列化/反序列化失败抛 `IllegalStateException`（原实现只记日志） |
| Profile 删除不删历史 | 只删 `profiles/<name>.yaml`，不触碰 sessions/llm_calls/tool_invocations |
| 12 个命令可发现 | `CommandHelpTest` 校验根帮助含全部命令 |
| 不新增模块与第三方依赖 | 9 模块不变 |

### 偏差 1：进程入口不是 `OryxOsCli` 而是 `OryxosLauncher`

计划原文假定 `OryxOsCli` 是 `main`。实际实现发现两个问题：重命令需要 Spring 上下文，而 `oryxos-cli` 不能依赖 Spring（否则轻命令也会被拖进 Spring 类路径）；同时"无参数启动 Web 服务"的既有行为必须保留。

**处理**：新增 `oryxos-boot` 的 `OryxosLauncher` 作为唯一进程入口，`OryxosCli` 退化为 Picocli 根命令。分流判定 `CommandModes` 放在无 Spring 依赖的 `oryxos-cli` 里，用模块依赖把"轻命令不得启 Spring"这条约束固化下来。

### 偏差 2：轻重判定位置从"启动协调器"前移到 `main` 第一行

计划把分流放在"重命令启动协调器"里。但那样 `profile list` 也要先创建 Spring 上下文，达不到"秒回"。

**处理**：判定前移到 `main` 的最前面，用纯字符串比较（扫描 argv 取第一个非选项 token），全程零 Spring 依赖。

### 偏差 3：原计划未预见 Windows 控制台编码问题

计划只写了"输出不泄露敏感信息"，未涉及终端编码。实现后暴露出两条独立路径的乱码：

- `System.out.println(String)` 走 `PrintStream` 自身 charset（中文系统取 ANSI 代码页 GBK）
- Logback 按字节写 `System.out`，内容用 UTF-8

**处理**：`CliChannel` 交互式终端改走 `System.console()`；`oryxos-boot` 新增 `ConsoleEncoding` 在 Spring 之前接管 `System.out`/`System.err`；`application.yaml` 固定 `logging.charset.console=UTF-8`。无控制台（管道/重定向/IDE 捕获/测试）时全部 no-op，配套 `ConsoleEncodingTest` 守住该不变量。

### 偏差 4：`init` 生成的默认 Profile 结构错误

原计划的 `init` 模板写成 `provider: default`、`channels: [cli]`，与 `Profile.ProviderConfig`（对象）和 `List<ChannelConfig>`（对象列表）不符，导致 `chat` 启动即报 `Profile 不存在`。

**处理**：修正模板结构、统一 `.yaml` 后缀、补齐 `AGENTS.md`/`SOUL.md`/`USER.md`，并新增**调用真实 `ProfileLoader`** 的回归测试（而不是只断言文件存在）。

### 偏差 5：`serve`/`gateway` 的交付深度

计划表述为"启动 HTTP 服务 / 启动常驻运行模式"。实际交付的是**启动接线**：

- `serve` 用 `WebApplicationType.SERVLET` 启动 Spring，复用 `oryxos-web` 既有 REST 控制器，`--port` 经 `--server.port` 覆盖，常驻不退出。
- `gateway` 用 `WebApplicationType.NONE` 启动共享运行时并常驻，**未挂载多渠道与路由**（IM Channel 属扩展阶段）。

该边界已在 `tasks.md` 的「serve / gateway 已实现边界」表中明确标注，避免被读成"多渠道网关已完成"。

### 偏差 6：新增运行包装脚本

计划未包含"如何安装 `oryxos` 命令"。实际新增 `bin/oryxos.cmd`（非 Maven 模块），并记录硬约束：必须纯 ASCII + CRLF + 无 BOM——`.cmd` 由 cmd.exe 按控制台代码页解析，混入非 ASCII 后会把一行拆成几截当命令执行。

## Complexity Tracking

无宪法违规项，本表留空。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| — | — | — |
