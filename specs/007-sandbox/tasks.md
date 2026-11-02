---
description: "Sandbox 统一抽象与白名单校验实施任务"
---

# Tasks: Sandbox 统一抽象与白名单校验

**Input**: Design documents from `/specs/007-sandbox/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/sandbox.md`、`quickstart.md`

**Tests**: 本特性明确要求测试（`contracts/sandbox.md` 第 5 节共 18 项），测试任务包含在各用户故事中。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。

**Status legend**: `[X]` 已完成并验证；`[ ]` 未完成（附原因）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: 所属用户故事（US1 ~ US4）

## Path Conventions

单体 Maven 多模块仓库，路径均为仓库根相对路径。**全部改动落在 `oryxos-tool`**，
另 `oryxos-boot` 的 `application.yaml`（注释）、`docs/` 与 `website/` 文档随之同步。

---

## Phase 1: Setup

- [X] T001 确认 `oryxos-tool` 测试依赖（JUnit 5 / Mockito / AssertJ）已就位，无需新增依赖
- [X] T002 [P] 复读 `docs/class/第23节`、`第24节` 与 `specs/005-tool-system/contracts/tool-invocation.md`，
      锁定"保留哪些既有行为"清单（真实路径、通配、开关、空白条目）

**Checkpoint**: 迁移清单明确，`mvn -pl oryxos-tool -am test` 可执行。✅

---

## Phase 2: Foundational（阻塞全部用户故事）

- [X] T003 新增 `oryxos-tool/.../sandbox/ActionType.java`：`FILE_ACCESS` / `SHELL_EXEC` / `HTTP_REQUEST`
- [X] T004 [P] 新增 `.../sandbox/SandboxAction.java`：`record (ActionType type, String target)`，
      不含任何实现概念
- [X] T005 [P] 新增 `.../sandbox/Sandbox.java`：`void enforce(SandboxAction action)`，唯一入口
- [X] T006 新增 `.../sandbox/WhitelistSandbox.java`：迁入 `SandboxChecker` 全部校验逻辑，
      三个校验方法降为 `private`，`isEnabled()` 保留供装配层与测试观察；
      另加"空动作/空目标"守卫，避免 NPE 冒给工具层
- [X] T007 删除 `.../sandbox/SandboxChecker.java`（不留第二条调用路径）

**Checkpoint**: 编译通过；生产代码中已无 `checkFilePath` / `checkShellCommand` / `checkHttpUrl` 的公开入口。✅

---

## Phase 3: User Story 1 - 四个工具统一经过一道沙箱墙 (Priority: P1)

- [X] T008 [US1] `ToolAutoConfiguration`：Bean 改为 `Sandbox sandbox(SandboxProperties)` 返回
      `WhitelistSandbox`；空白条目校验与关闭告警原样保留；登记器改依赖 `Sandbox`
- [X] T009 [P] [US1] `FileTool`：构造参数改 `Sandbox`，`execute` 首行（参数校验后）`enforce(FILE_ACCESS)`
- [X] T010 [P] [US1] `ShellTool`：同上，`enforce(SHELL_EXEC)`
- [X] T011 [P] [US1] `HttpTool`：同上，`enforce(HTTP_REQUEST)`
- [X] T012 [US1] `NotifyTool`：构造参数改 `Sandbox`（**非空**，`requireNonNull`），删除"未装配则放行 + WARN"分支；
      目标解析后、发送前 `enforce(HTTP_REQUEST, url)`；`NotifyAutoConfiguration` 改为直接注入
- [X] T013 [P] [US1] `FileToolTest`：构造改 `WhitelistSandbox`；越界写入断言文件未创建
- [X] T014 [P] [US1] `ShellToolTest`：同上；越界断言探针目录未创建（无进程启动）
- [X] T015 [P] [US1] `HttpToolTest`：同上；越界断言假服务零命中
- [X] T016 [P] [US1] `NotifyToolTest`：mock `Sandbox`，`inOrder` 校验 `enforce` 的参数与"先校验后发送"；
      原"未装配校验器时放行"用例改为"没有沙箱时拒绝构造工具"
- [X] T017 [US1] `ToolAutoConfigurationTest`：Bean 类型随抽象调整（补"Bean 类型是接口"用例）；
      关闭开关用例改为断言三个动作被放行
- [X] T018 [US1] `NotifyAutoConfigurationTest`：上下文补 `Sandbox` Bean；去掉"未装配仍可构造"用例，
      改为用 `ReflectionTestUtils` 断言工具持有的就是容器里那个沙箱

**Checkpoint**: 四个工具的"放行 + 越界"路径全绿，越界副作用为零。✅

---

## Phase 4: User Story 2 - 绕过手法被固定行为拦住 (Priority: P1)

- [X] T019 [US2] 新增 `WhitelistSandboxTest`（由 `SandboxCheckerTest` 改写）：三维度放行/拒绝、
      空清单全拒、`..` 穿越、符号链接三例、关闭开关三例逐条保留
- [X] T020 [US2] 扩充绕过用例：`*.example.com` 命中 `api.example.com` 且不命中 `evil-example.com`；
      带端口地址按 host 判定；命令前导空白放行、大小写变体拒绝；空动作/空目标拒绝且消息可读
- [X] T021 [US2] 删除旧 `SandboxCheckerTest`

**Checkpoint**: 绕过用例 100% 拦截，0 跳过（23 例全绿）。✅

---

## Phase 5: User Story 3 - 拒绝可读、可审计、不假装成功 (Priority: P2)

- [X] T022 [US3] 断言拒绝消息含"维度 + 被拒值"（三个维度各一条）
- [X] T023 [US3] 断言四个工具的拒绝结果均为 `retryable=false`，且失败原因原样透出
- [X] T024 [US3] 确认拒绝不新增审计路径：`ToolExecutor` 未改动，落库由既有链路覆盖（代码走查）

**Checkpoint**: 拒绝原因人能读懂、模型能改道，审计口径不变。✅

---

## Phase 6: User Story 4 - 接口中立性可被复核 (Priority: P3)

- [X] T025 [US4] `contracts/sandbox.md` 第 1.1 节给出 microVM 反向推演，并声明"接口不加方法"
- [X] T026 [US4] 代码走查：`Sandbox` 签名与 `SandboxAction` 字段中无实现词（白名单/容器/镜像/路径/命令/域名）
- [X] T027 [US4] 走查四个工具的字段类型均为 `Sandbox`，生产代码中无 `WhitelistSandbox` 的直接引用
      （装配层除外）

**Checkpoint**: 换实现只新增一个类，调用点零改动。✅

---

## Phase 7: Polish & Cross-Cutting

- [X] T028 [P] `oryxos-boot/src/main/resources/application.yaml`：注释对齐"统一沙箱 + notify 同受约束 +
      命令大小写敏感"（键名不变）
- [X] T029 同步文档：`docs/class/第24节` 补「附：仓库实现说明」；`docs/TechnicalSolution.md` §6.2 / §6.7 /
      模块表 / 场景串讲；`oryxos-tool/pom.xml` 描述；`website/{docs,zh/docs}` 三处类名
- [X] T030 使用 JDK 21 + 固定 Maven 3.9.9 执行 `mvn -pl oryxos-tool -am test`，再执行仓库根 `mvn test`
- [X] T031 回填本文档完成状态与验证记录
- [ ] T032 按 `quickstart.md` 第 4 节完成人工集成验证（真实白名单、审计落库、失败原因可读）
      **待使用方执行**：需要模型凭证或直接调用工具的真实链路；单测已覆盖"拒绝 + 副作用为零"，
      未覆盖的是"真实 `tool_invocations` 落库"这一条

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → 全部用户故事
- US1 依赖 Phase 2（没有墙就没有接线）
- US2 依赖 Phase 2（绕过用例直接测实现）
- US3 依赖 US1（断言点在工具）
- US4 依赖 Phase 2（只走查抽象）

### 不可拆分的约束

**T006 与 T007 必须一起交付**：保留 `SandboxChecker` 等于把绕过墙的路留着，
"两个名字表示同一件事"必然导致后来者走错。本轮已合并提交。

### Parallel Opportunities

- T003 / T004 / T005 可并行；T009 / T010 / T011 可并行
- T013 ~ T016 可并行（四个不同测试文件）
- 注意：`ToolAutoConfiguration.java` 由 T008 单独修改，`NotifyTool.java` 与 `NotifyAutoConfiguration.java`
  由 T012 一起改

## 实现期决策（计划外）

| # | 决策 | 理由 |
|---|---|---|
| 1 | `WhitelistSandbox.enforce` 增加"动作/目标为空"守卫 | `Paths.get(null)` 会把 NPE 冒给工具层，变成一条不可读的失败；守卫后统一走 `SandboxViolationException` |
| 2 | 删除 `SandboxChecker` 而非保留为内部类 | 见 spec C-1：墙要唯一，公开三方法是第二条绕过路径 |
| 3 | `NotifyTool` 用 `requireNonNull` 而非保留可空分支 | 005 留下的"未装配则放行 + WARN"正是本特性要消灭的状态 |
| 4 | `ToolAutoConfigurationTest` 新增"Bean 类型是接口"用例 | 静态可断言的自查：若哪天有人把 Bean 返回类型改回具体类，用例立刻红 |
| 5 | 同步 `docs/` 与 `website/` 中已失真的类名 | 删类后文档指向不存在的类型，比"文档少写一节"更容易误导 |

## 验证记录

| 验证项 | 方式 | 结果 |
|---|---|---|
| 工具模块回归 | `mvn -pl oryxos-tool -am test`（JDK 21 + Maven 3.9.9） | **BUILD SUCCESS**；`oryxos-tool` 113 例全绿，其中 `WhitelistSandboxTest` **23 例、0 跳过** |
| 全量回归 | 仓库根 `mvn test` | **10 个模块 BUILD SUCCESS** |
| 公开入口数量 | 代码走查 | `Sandbox` 只有 `enforce`；三个校验方法为 `WhitelistSandbox` 私有方法 |
| 接线静态类型 | 代码走查 + `ToolAutoConfigurationTest` | 四个工具字段类型均为 `Sandbox`；Bean 返回类型为接口 |
| 副作用为零 | 四个 Tool 的越界用例 | 文件未创建 / 探针目录未创建 / 假服务零命中 / 适配器零调用 |
| 绕过用例 | `WhitelistSandboxTest` | `..` 穿越、符号链接 3 例、`evil-example.com`、大小写变体全部拦截 |
| 真实链路（审计落库） | 人工 | ⏳ 待使用方执行（T032） |

## Notes

- 不新增 Maven 模块、不新增第三方依赖
- 不使用 `@Tool` 注解注册（宪法 III）
- 审计落库路径未被绕过（宪法 VI）
- 删除 `SandboxChecker` 属破坏性改动，理由见 `plan.md` Complexity Tracking
- 未执行 Git commit（当前目录不是 git 仓库）
