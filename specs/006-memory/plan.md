# Implementation Plan: Memory（长期记忆、核心/归档分区、记忆读写工具）

**Branch**: `006-memory` | **Date**: 2026-10-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-memory/spec.md`

## Summary

把"Agent 记得住事"这条链路从**断线**接到**能跑**：core 定义统一门面 `MemoryService`（含 `MemoryScope`），`oryxos-memory` 提供文件式实现（`LongTermMemory` 读写 `memory/MEMORY.md` 的核心/归档两区块）与两个内置工具（`save_memory` / `recall_memory`），`PromptBuilder` 第二部分改为注入长期记忆，`oryxos init` 改为在规范路径创建记忆文件并迁移旧位置内容。

开工前已就 4 处冲突拍板（见 spec.md 第 4 章），本计划按此执行：

| 编号 | 结论 | 落地位置 |
|---|---|---|
| C-001 | 门面接口上移到 core，旧读端口退役 | `oryxos-core/.../memory/`、`CoreAutoConfiguration` |
| C-002 | 第二部分只注入长期记忆，不重复注入会话历史 | `PromptBuilder` |
| C-003 | 采纳核心/归档两区块 + `scope` 参数，并同步旧文档 | `LongTermMemory`、`MemoryTools`、`docs/`+`website/` |
| C-004 | 初始化改到规范路径，旧位置内容迁移一次 | `WorkspaceLayout` + `MemoryFiles` |

## Technical Context

**Language/Version**: Java 21（根 pom `maven.compiler.release=21`）

**Primary Dependencies**: Spring Boot 3.3.5（装配层只用 `spring-context`/`spring-boot` 级别注解）、SLF4J + Logback、JUnit 5 + AssertJ（经 `spring-boot-starter-test`，test scope）

**Storage**: 工作区文件系统 `<workspace>/memory/MEMORY.md`（UTF-8 明文 Markdown，只追加，读取时截断）；会话记忆仍走 SQLite，本特性不改表结构

**Testing**: `mvn test`（纯 JUnit 5 + AssertJ，文件类用 `@TempDir`）；不引入 `@SpringBootTest`

**Target Platform**: 服务端 JVM（单二进制 fat JAR），Windows / Linux

**Project Type**: Maven 多模块单体应用（改动落在 core / memory / cli / boot 四处 + 文档）

**Performance Goals**: 每次组装 Prompt 读一次记忆文件（几 KB 量级），不做缓存（FR-005）；4000 字符注入阈值保证上下文占用可控

**Constraints**: 零新增外部依赖（不引向量库）；不新增 Maven 模块；不改 SQLite 表结构；记忆工具不得依赖 Spring AI 的自动执行

**Scale/Scope**: 单工作区共享长期记忆（不按 Agent/用户隔离，A-001）；归档区 4000 字符为常量（A-003）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 结论 | 说明 |
|---|---|---|
| I. JDK 21 + Spring Boot 3.x 单体 + 9 模块 | PASS | 不新增模块；改动落在既有模块内 |
| II. 自实现 ReAct 循环 | PASS | 不触循环引擎逻辑（`ReActLoop` 不改） |
| III. Spring AI 只用一半（禁用自动 tool 执行） | PASS | 两个记忆工具实现 `OryxTool`，在装配处**显式登记**；第22节示例里的 `@Tool` 注解写法按"示意代码"处理（见 research P-02） |
| IV. Tool 体系统一管理 | PASS | 记忆工具类留在 `oryxos-memory`（属 Memory 能力），不新开模块、不拆 tool 子模块；注册进共享 `ToolRegistry` |
| V. Memory 统一门面 | PASS（含 1 处边界说明） | 门面成为引擎唯一记忆入口，会话记忆仍由 `SessionManager`/`Session` 承担、**未被合并**。差异点：会话历史不由门面输出（C-002 已拍板）——这是门面职责边界，不是取消门面 |
| VI. 审计表 day one 落库 | PASS | 记忆工具经既有 `ToolExecutor` 执行，自动写 `tool_invocations`（FR-018） |
| VII. 核心阶段范围控制 | PASS | 明确不做向量检索、情景记忆、自动提炼、记忆查询端点（FR-019） |
| 模块边界约束 | PASS | 端口在 core、实现在 memory（与 `SessionStore`↔`JpaSessionStore` 同构）；SKILL.md 仍归 `ContextLoader` |
| 关键设计约束 | PASS | 不使用 SecurityManager；SQLite 建表脚本不动；不做上下文压缩 |

**GATE 结论**：无违反项，无需宪法例外（Complexity Tracking 中不填违规项，只记录两处刻意取舍）。

## Project Structure

### Documentation (this feature)

```text
specs/006-memory/
├── plan.md                      # 本文件
├── spec.md                      # 特性规格（含 4 处澄清结论）
├── research.md                  # Phase 0：决策与取舍（D-01~D-10）
├── data-model.md                # Phase 1：实体、格式、不变式
├── contracts/                   # Phase 1：门面契约 / 文件格式契约 / 工具契约
├── quickstart.md                # Phase 1：怎么跑起来、怎么验
├── checklists/requirements.md   # 规格质量检查清单
└── tasks.md                     # Phase 2：任务清单（/speckit.tasks 产出）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── memory/
│   ├── MemoryService.java               # 【新】统一门面接口
│   ├── MemoryScope.java                 # 【新】CORE / ARCHIVAL
│   └── MemoryReader.java                # 【删】旧读端口，被门面取代
├── react/PromptBuilder.java             # 【改】依赖门面；第二部分只放长期记忆
└── config/CoreAutoConfiguration.java    # 【改】注入门面（ObjectProvider 保持可选语义）

oryxos-memory/src/main/java/io/oryxos/memory/
├── MemoryService.java                   # 【删】本地接口，上移到 core
├── DefaultMemoryReader.java             # 【删】随旧端口退役
├── DefaultMemoryService.java            # 【新】门面实现 → 委托 LongTermMemory
├── LongTermMemory.java                  # 【改】两区块、scope 路由、读取时截断、并发安全
├── MemoryFiles.java                     # 【新】路径/骨架/区块标题/迁移（init 与运行时共用）
├── MemoryTools.java                     # 【改】save_memory(content, scope) / recall_memory(keyword)
└── config/MemoryAutoConfiguration.java  # 【新】装配门面 + 显式登记两个工具

oryxos-memory/src/test/java/io/oryxos/memory/
├── LongTermMemoryTest.java              # 【新】写后立读 / 截断只裁归档 / scope 路由
├── MemoryToolsTest.java                 # 【新】scope 缺省、非法取值、未命中不报错
├── DefaultMemoryServiceTest.java        # 【新】门面只输出长期记忆
└── MemoryFilesTest.java                 # 【新】骨架幂等、迁移四条分支

oryxos-memory/pom.xml                    # 【改】+ spring-boot（装配）+ starter-test（测试）
oryxos-cli/pom.xml                       # 【改】+ oryxos-memory
oryxos-boot/pom.xml                      # 【改】+ oryxos-memory（进 fat JAR 与组件扫描）
oryxos-cli/src/main/java/io/oryxos/cli/WorkspaceLayout.java  # 【改】规范路径 + 迁移
oryxos-cli/src/test/java/io/oryxos/cli/InitCommandTest.java  # 【改】断言新路径与迁移

docs/TechnicalSolution.md                # 【改】§5.2 按 C-003 同步（两区块）
website/docs/memory.md                   # 【改】同步
website/zh/docs/memory.md                # 【改】同步
```

**Structure Decision**：沿用既有"端口在 core、实现在能力模块、装配在能力模块的 `@Configuration`、由 boot 组装"的四层惯例（与 005-tool-system 完全一致）。不新增 Maven 模块，不改数据库表，不动 `ReActLoop`。

## Complexity Tracking

> 无宪法违反项。下表记录两处"看起来像特例"的刻意取舍，供 review 时对照。

| 取舍 | 为什么需要 | 不这么做的代价 |
|---|---|---|
| 门面接口放 core，而不是留在 memory 模块 | 引擎（core）必须能依赖门面类型，而 core 不能反向依赖实现模块（否则依赖倒置） | 接口留在 memory 时，core 只能继续保留一层转接端口，与宪法 V"对 ReAct 只暴露一个 MemoryService"的表述不符（C-001） |
| 记忆文件格式常量独立成 `MemoryFiles` | 初始化（cli）与运行时（memory）必须写**同一份**骨架与区块标题 | 格式在 cli、memory 各写一遍，改一处忘一处就会出现"init 建的文件运行时读不出区块"——且这类缺陷在单测里很难暴露 |
