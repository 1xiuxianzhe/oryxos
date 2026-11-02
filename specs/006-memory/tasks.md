---
description: "Memory 长期记忆实施任务"
---

# Tasks: Memory（长期记忆、核心/归档分区、记忆读写工具）

**Input**: Design documents from `/specs/006-memory/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/`、`quickstart.md`

**Tests**: 本特性明确要求测试（spec.md 各用户故事的 Independent Test + 契约文件的行为约定），测试任务内嵌在各用户故事中，不单列测试阶段。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。

**Status legend**: `[X]` 已完成并验证；`[ ]` 未完成（附原因）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: 所属用户故事（US1 ~ US4）

## Path Conventions

单体 Maven 多模块仓库，路径均为仓库根相对路径。主体落在 `oryxos-memory`；
另有 `oryxos-core`（门面接口与引擎接线）、`oryxos-cli`（初始化与迁移）、
`oryxos-boot`（依赖组装）三处收口改动；文档同步涉及 `docs/` 与 `website/`。

---

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 [P] `oryxos-memory/pom.xml` 增加依赖：`org.springframework.boot:spring-boot`（装配注解，对齐 `oryxos-tool` 的做法）
      与 `org.springframework.boot:spring-boot-starter-test:test`（测试）
- [X] T002 [P] 依赖组装：`oryxos-cli/pom.xml` 增加 `io.oryxos:oryxos-memory`（初始化要复用文件格式与迁移逻辑）；
      `oryxos-boot/pom.xml` 显式增加 `io.oryxos:oryxos-memory`（进 fat JAR 与组件扫描，见 research P-01）
- [X] T003 建立改动前基线：按 quickstart 第 0/1 节设 `JAVA_HOME` 并执行 `mvn -q test`，确认改动前全绿

**Checkpoint**: `mvn -q test` 在改动前是绿的（后续每一步失败都能定位到本次改动）。✅

---

## Phase 2: Foundational (Blocking Prerequisites)

- [X] T004 [P] 新增 `oryxos-core/src/main/java/io/oryxos/core/memory/MemoryScope.java`：枚举 `CORE` / `ARCHIVAL`（含语义 Javadoc）
- [X] T005 [P] 新增 `oryxos-core/src/main/java/io/oryxos/core/memory/MemoryService.java`：三个方法
      （`buildContext(Session)` / `remember(String, MemoryScope)` / `recall(String)`），契约见 `contracts/memory-service.md` C-1~C-9
- [X] T006 新增 `oryxos-memory/src/main/java/io/oryxos/memory/MemoryFiles.java`：路径解析（`memory/MEMORY.md`）、骨架常量、
      区块标题常量、`initialize(workspace)`、`migrateLegacy(workspace)` 与 `LegacyMigrationResult`，
      严格按 `contracts/memory-file-format.md` 第 1/2/5/6 节
- [X] T007 重写 `oryxos-memory/src/main/java/io/oryxos/memory/LongTermMemory.java`：
      `append(content, scope)` / `load()` / `recallByKeyword(keyword)`；两区块容错解析；**截断只作用于渲染路径**（不落盘）；
      多行内容缩进续行；单实例锁串行化"补骨架 + 插入条目"（research D-03/D-04/D-05）
- [X] T008 新增 `oryxos-memory/src/main/java/io/oryxos/memory/DefaultMemoryService.java`：门面实现，
      读取路径一律降级为空内容 + WARN，写入路径失败向上抛（research D-06）
- [X] T009 改 `oryxos-core/src/main/java/io/oryxos/core/react/PromptBuilder.java`：构造依赖 `MemoryService`；
      第二部分改用 `buildContext(session)`；删除 `lastUserMessage(...)`（research D-02）
- [X] T010 改 `oryxos-core/src/main/java/io/oryxos/core/config/CoreAutoConfiguration.java`：
      `ObjectProvider<MemoryReader>` → `ObjectProvider<MemoryService>`，保持"缺失即安全默认"的可选语义
- [X] T011 删除退役类型：`oryxos-core/.../core/memory/MemoryReader.java`、
      `oryxos-memory/.../DefaultMemoryReader.java`、`oryxos-memory/.../MemoryService.java`（本地接口，已上移 core）
- [X] T012 改 `oryxos-memory/src/main/java/io/oryxos/memory/MemoryTools.java`：
      `save_memory`（`content` 必填 + `scope` 可选）、`recall_memory`（`keyword` 必填）的 schema、参数解析、
      失败结果与可重试性，严格按 `contracts/memory-tools.md`
- [X] T013 新增 `oryxos-memory/src/main/java/io/oryxos/memory/config/MemoryAutoConfiguration.java`：
      `LongTermMemory` Bean（`@Value("${oryxos.workspace:...}")` + `MemoryFiles.resolve`）、`MemoryService` Bean、
      以及把两个工具**显式登记**进共享 `ToolRegistry` 的登记器（对齐 `BuiltinToolRegistration` 的写法，research D-07/D-10）
- [X] T014 更新 `oryxos-core/src/test/java/io/oryxos/core/react/PromptBuilderTest.java` 到门面签名，
      恢复编译绿线（语义断言在 US2 补齐）

**Checkpoint**: `mvn -q -pl oryxos-core,oryxos-memory -am test` 可执行且绿；应用能启动（记忆段可能仍为空，属正常）。

---

## Phase 3: User Story 1 - Agent 能记住并想起来 (Priority: P1)

**Goal**: 两个工具真正可用：写入后立读、检索命中、未命中不报错、非法参数被明确拒绝。

**Independent Test**: 用 `@TempDir` 工作区直接构造 `LongTermMemory` / `DefaultMemoryService` / 两个工具，全部脱离模型与外网。

- [X] T015 [US1] 新增 `oryxos-memory/src/test/java/io/oryxos/memory/LongTermMemoryTest.java`（US1 部分）：
      写入后立刻可读（无缓存）；父目录不存在时自动创建；文件缺失时 `load()` 为空；
      写入失败（父路径为文件）时抛异常而非静默吞掉
- [X] T016 [US1] 新增 `oryxos-memory/src/test/java/io/oryxos/memory/MemoryToolsTest.java`：
      `save_memory` 成功后确认文本含写入标识；缺 `content` → 失败且 `retryable=false`；
      `recall_memory` 命中返回条目、未命中返回"没有找到相关记忆"（`success=true`，不抛异常）；缺 `keyword` → 失败
- [X] T017 [US1] 新增 `oryxos-memory/src/test/java/io/oryxos/memory/config/MemoryAutoConfigurationTest.java`：
      装配后 `ToolRegistry` 含 `save_memory`/`recall_memory`；记忆文件路径落在 `<workspace>/memory/MEMORY.md`；
      `MemoryService` Bean 可注入（直接 `new` 装配类 + 断言，与 `ToolAutoConfigurationTest` 同风格）
- [X] T018 [US1] 端到端链路断言：用 `ToolExecutor` + 假审计器执行两个工具，断言审计被记录、
      工具失败时返回失败结果而非抛异常（FR-018，复用或参照既有 `ToolExecutor` 测试写法）
- [X] T019 [US1] 校验：`mvn -q -pl oryxos-memory test` 全绿

**Checkpoint**: US1 可独立演示——"说一句值得记的话 → 文件里多一条 → 检索能找回来"。

---

## Phase 4: User Story 2 - 每次新对话都自带长期记忆 (Priority: P1)

**Goal**: 组装 Prompt 时自动带上长期记忆，且不与会话历史重复；记忆缺失时对话不受影响。

**Independent Test**: 不依赖模型，构造带内容的记忆文件并断言 `PromptBuilder` 输出的第二部分内容。

- [X] T020 [US2] 新增 `oryxos-memory/src/test/java/io/oryxos/memory/DefaultMemoryServiceTest.java`：
      `buildContext` 返回核心区 + 归档区；文件缺失/缺区块/路径是目录 → 空串且不抛异常；
      `remember` 后同实例 `buildContext`/`recall` 立即可见；`recall(null/空白)` 返回空列表而非全量
- [X] T021 [US2] 改造 `PromptBuilderTest`：断言 `request.memory()` 等于门面输出；断言该内容在整份请求里**只出现一次**
      （不与第三部分会话历史重复）；门面为 `null` 时为空串且 `hasMemory()` 为 `false`
- [X] T022 [US2] 断言 `buildContext` **不含会话历史**：用带多轮历史的 `Session` 验证门面输出里没有历史消息内容（C-002/FR-009）

**Checkpoint**: US2 可独立演示——预置一份 `MEMORY.md`，新会话的模型输入里就有它。

---

## Phase 5: User Story 3 - 核心记忆永远在场 (Priority: P2)

**Goal**: 核心区不被截断吃掉；分类由 Agent 决定；检索只看归档区。

**Independent Test**: 先写核心记忆，再把归档区灌到远超阈值，断言核心逐字节完整、归档只留最近。

- [X] T023 [US3] `LongTermMemoryTest` 补用例：核心记忆一字不动（对照截断前后）；
      归档超 4000 字符只保留最近、最早被丢弃；**截断不落盘**（截断前后文件字节一致）；
      `scope` 路由到正确区块；`recallByKeyword` 只搜归档区（核心区内容不被检索返回）；
      多行内容与含 `## ` 的内容不破坏区块结构；区块缺失/顺序颠倒/额外自由文本的容错；并发 `append` 不丢内容
- [X] T024 [US3] `MemoryToolsTest` 补用例：`scope` 缺省落归档；`scope` 非法值 → 失败且 `retryable=false`；
      `scope=core` 写入后 `recall_memory` **不返回**它

**Checkpoint**: 第 22 节点名的"最容易被优化掉的坑"被回归测试钉死。

---

## Phase 6: User Story 4 - 初始化把记忆文件放到规范位置 (Priority: P3)

**Goal**: `oryxos init` 在规范路径创建记忆文件，并按契约处理旧位置遗留文件。

**Independent Test**: 不依赖模型，在 `@TempDir` 跑初始化并检查文件树与文件内容。

- [X] T025 [US4] 新增 `oryxos-memory/src/test/java/io/oryxos/memory/MemoryFilesTest.java`：
      骨架幂等（重复初始化不覆盖、不清空）；迁移四场景（`NO_LEGACY`/`NOTHING_TO_MIGRATE`/`MIGRATED`/`SKIPPED_TARGET_NOT_EMPTY`）；
      `.bak` 命名规则与"目标已有非空内容不迁移"
- [X] T026 [US4] 改 `oryxos-cli/src/main/java/io/oryxos/cli/WorkspaceLayout.java`：
      新增 `memoryFile()` 指向 `memory/MEMORY.md`；`initialize()` 改为 `MemoryFiles.initialize` +
      `migrateLegacy`，**不再**在根目录创建 `MEMORY.md`（C-004）
- [X] T027 [US4] 改 `oryxos-cli/src/test/java/io/oryxos/cli/InitCommandTest.java`：
      断言 `memory/MEMORY.md` 存在且含两个区块标题、工作区根**不再**有 `MEMORY.md`；
      新增"根目录遗留有内容 → 迁移并改名备份"用例；重复执行 `init` 仍幂等

**Checkpoint**: US4 可独立演示——干净目录 `init` 后文件树正确；带遗留文件时内容不丢。

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T028 [P] 同步旧文档（C-003）：`docs/TechnicalSolution.md`（§5.1 MemoryService/LongTermMemory/MemoryTools 描述、
      §5.2 文件设计与两区块、§5.3 注入语义）、`website/docs/memory.md`、`website/zh/docs/memory.md`
- [X] T029 全量回归：`mvn -q test`，确认既有测试全绿（SC-006）
- [X] T030 启动一次真实应用，按 quickstart 第 2 节核对启动日志（工具注册含两个记忆工具、记忆装配路径正确）
- [X] T031 人工验收 Demo 二（跨会话 + 跨重启 + 核心记忆不被截断）——**已用真实模型验证**（2026-10-19）：
      核心区落 1 条长期约束（模型自行判定为 `core`），归档区落 2 条事实，写入规范路径 `.oryxos/memory/MEMORY.md`
- [X] T032 跑 `/speckit.analyze` 做跨 artifact 一致性检查；把实现期偏离与决策追加到本文件末尾

---

## Dependencies & Execution Order

### Phase Dependencies

```text
Phase 1 Setup → Phase 2 Foundational → { Phase 3 US1 → Phase 5 US3 } 、Phase 4 US2 、Phase 6 US4 → Phase 7 Polish
```

- **Phase 2 不可跳过**：门面接口、文件格式、装配三者互为前提；缺任一环，运行时记忆链路仍是断的。
- **US1 与 US2 并行**：US1 走工具 → 文件路径，US2 走门面 → Prompt 路径，落点不同文件（工具/门面测试类互不重叠）。
- **US3 依赖 US1**：截断与分区用例复用 US1 建立的 `LongTermMemoryTest`。
- **US4 相对独立**：只碰 cli，但依赖 T006 的 `MemoryFiles`。
- **T011（删旧类型）必须在 T009/T010 之后**，否则 core 编译不过。

### 不可拆分约束

- T006（格式常量）与 T007/T026（读写、初始化）必须**共用同一份常量**：任何"两处各写一遍区块标题"的实现都算未达标。
- T009 与 T014 必须同批完成：`PromptBuilder` 签名一变，core 测试立刻编译失败。
- T020 的"截断只裁归档"必须有**核心区逐字节不变**的断言，不接受"看起来没被截掉"。

### Parallel Opportunities

- T001、T002（pom 改动，不同文件）可并行。
- T004、T005（core 两个新文件）可并行。
- T015、T016、T017（三个不同测试类）可并行。
- T028（三份文档）可与测试收尾并行。

---

## Notes

- 本特性**不新增 Maven 模块**、**不改 SQLite 表结构**、**不用 Spring AI 的 `@Tool` 注解与自动执行**。
- 所有测试走"纯 JUnit 5 + 直接 new 被测对象 + `@TempDir`"，不引入 `@SpringBootTest`。
- 记忆工具不需要改 `ToolResolver`：`Profile.tools` 为空即"不限制"（默认模板就是空清单）。
- 实现期若发现与 spec/contracts 冲突，先在本文件"实现期决策"追加记录，再继续。

## 实现期决策（计划外）

1. **BOM 容错**（T030 端到端验证时暴露的缺陷）：用真实 fat JAR 跑 `init` 时发现，PowerShell 写出的遗留文件带 UTF-8 BOM（`U+FEFF`），使首行 `# OryxOS Memory` 被当作一条普通记忆迁进归档区（`strip()` 不会去掉 BOM，`startsWith("#")` 判定失效）。已在 `MemoryFiles.stripBom` 统一处理，并在三处生效：读记忆文件、读遗留文件、判定规范位置是否已有内容。补两条回归：`MemoryFilesTest.带BOM的遗留文件_标题行不会被当成记忆条目`、`LongTermMemoryTest.带BOM的记忆文件仍能识别区块`；`contracts/memory-file-format.md` 已同步这条容错要求。
2. **`migrateLegacy` 不创建文件**（T025 实现时澄清职责边界）：迁移函数只做"搬内容 + 备份旧文件"，创建骨架归 `initialize`；`NO_LEGACY` / `NOTHING_TO_MIGRATE` 两种场景**不产生任何文件副作用**。契约第 6 节的措辞已据此修正（原文"规范文件已创建"容易被读成迁移函数的职责）。
3. **区块边界按"行首 `## `"判定**：写入内容里的 `## ` 因缩进续行而不构成新区块。对应的测试用 `\n` + 标题计数（而不是裸 `indexOf`）断言，否则会把缩进的内容误算成第二个标题。
4. **截断不写回磁盘**：`truncateIfNeeded` 从公开 API 中移除，截断改为 `load()` 内部的纯读操作。好处是文件永远只追加（并发写不会互相破坏），代价是归档区在磁盘上会持续增长——这是刻意的取舍，记在 `research.md` D-04。

## 验证记录

| 阶段 | 命令 | 结果 |
|---|---|---|
| 改动前基线 | `mvn -q test` | BUILD SUCCESS（作为对照基线） |
| Phase 3-6（core / memory / cli） | `mvn -pl oryxos-core,oryxos-memory,oryxos-cli -am test` | core 73 / memory 41 / cli 20 / channel-cli 2，全绿 |
| BOM 修复 + 注入重复断言 | `mvn -pl oryxos-memory -am test`、`mvn -pl oryxos-core test` | memory 43 / core 74，全绿 |
| T029 最终全量回归 | `mvn test` | BUILD SUCCESS；各模块 74 / 24 / 43 / 107 / 14 / 18 / 2 / 20 / 5，Failures 与 Errors 均为 0 |
| T030 产物级验证 | `mvn -pl oryxos-boot -am -DskipTests package` + `java -jar … init` | fat JAR 内含 `BOOT-INF/lib/oryxos-memory-0.1.0-SNAPSHOT.jar`（证明模块真的进了运行时 classpath）；干净工作区 `init` 建出 `memory/MEMORY.md`（两区块）；带 BOM 的遗留文件被正确迁移（仅 1 条条目，无标题行污染），旧文件改名 `MEMORY.md.bak`，根目录不再有 `MEMORY.md` |
| T032 一致性检查 | 逐条比对 spec FR ↔ contracts ↔ 代码 ↔ 测试 | 19 条 FR 均有落点与测试；`MemoryReader` 已全仓库无残留引用；无 `@Tool` 注解引入；`USER.md` 无写入路径 |

| T031 真实模型 Demo 二 | 真实大模型对话 + 目检 `.oryxos/memory/MEMORY.md` | 核心区 1 条（"每次回答结尾加一句祝你愉快ljx"，模型自行判定为 core）、归档区 2 条（技术栈 / 部署形态澄清）；跨会话、跨重启均生效 → **SC-001 通过** |

**未执行项**：无。SC-001~SC-006 全部有对应证据（测试或真实模型走查）。

**一处偏差记录**：`spec.md` A-003 约定 4000 字符阈值为常量，实现按此落地（`LongTermMemory.MAX_ARCHIVE_CHARS`）；`website` 与 `TechnicalSolution.md` 中"阈值只作用于归档区"的表述已同步。
