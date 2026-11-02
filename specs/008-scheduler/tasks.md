---
description: "定时任务模块实施任务"
---

# Tasks: 定时任务模块（AgentScheduler）

**Input**: Design documents from `/specs/008-scheduler/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/scheduler.md`、`quickstart.md`

**Tests**: 本特性明确要求测试（`contracts/scheduler.md` 第 5 节共 14 项），测试任务包含在各用户故事中。

**Status legend**: `[X]` 已完成并验证；`[ ]` 未完成（附原因）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: 所属用户故事（US1 ~ US4）

## Path Conventions

单体 Maven 多模块仓库，路径均为仓库根相对路径。全部改动落在 `oryxos-core`，
另 `oryxos-boot` 的 `application.yaml`、`.oryxos/profiles/default.yaml` 与 `docs/` 同步。

---

## Phase 1: Setup

- [X] T001 确认 core 已具备 `spring-context`（`TaskScheduler` / `ThreadPoolTaskScheduler` / `CronTrigger`），无需新增依赖
- [X] T002 [P] 用 `javap` 确认 `CronTrigger` 提供 `CronTrigger(String, ZoneId)` 与 `getExpression()`（实测 spring-context 6.2.3）

## Phase 2: Foundational（阻塞全部用户故事）

- [X] T003 扩展 `Profile.ScheduleConfig`：新增 `id` / `timezone` / `message`，保留 `cron` 与 `task`，写清 `message` 优先于 `task`
- [X] T004 新增 `oryxos-core/.../schedule/AgentScheduler.java`：常量 `CHANNEL=scheduler` / `USER_ID=scheduler`、
      锁表、已注册任务集合、Future 列表、`taskKey(profileName,id)`
- [X] T005 `CoreAutoConfiguration` 产出 `ThreadPoolTaskScheduler` Bean（`oryxos.scheduler.pool-size`，默认 2，
      线程名前缀 `oryxos-scheduler-`，errorHandler 记 ERROR）与 `AgentScheduler` Bean

**Checkpoint**: 容器能装配出调度器；无 `schedules` 时注册 0 条。✅

---

## Phase 3: User Story 1 - 到点自动跑，走同一入口 (Priority: P1)

- [X] T006 [US1] `registerAll()`：遍历 Profile 的 `schedules`，校验后 `CronTrigger(cron, zone)` 注册，
      记录注册条数并打 INFO
- [X] T007 [US1] `runOnce(profile, config)`：`getOrCreate("scheduler","scheduler",profileName)` →
      `agentService.process(session, message)`；消息取 `message`，回退 `task`
- [X] T008 [US1] `afterPropertiesSet()` 调用 `registerAll()`；`destroy()` 取消全部 Future
- [X] T009 [P] [US1] `AgentSchedulerTest`：注册参数（cron + 时区语义，固定时钟断言 01:00Z）、无配置注册 0 条、
      触发调用一次、会话三元组固定且两次触发同一 Session、`task` 回退

---

## Phase 4: User Story 2 - 上一次没跑完直接跳过 (Priority: P1)

- [X] T010 [US2] 锁表实现：`lockFor(profileName, id)` + `tryLock()`；拿不到锁 INFO 跳过
- [X] T011 [US2] `AgentSchedulerTest`：锁占用时零调用；不同 Profile 同名 id 不互相阻塞；释放后可再次进入
      **测试要点**：占锁必须来自**另一个线程**——`ReentrantLock` 允许同线程重入，在测试线程里 `lock()`
      再调 `runOnce` 会拿到锁，"跳过"根本测不到（首版就踩了这个坑，已改成独立线程持锁）

---

## Phase 5: User Story 3 - 失败不崩、留痕可查 (Priority: P2)

- [X] T012 [US3] `runOnce` 内 `catch` + `finally unlock()`：异常不外抛，ERROR 含任务键与原因
- [X] T013 [US3] `AgentSchedulerTest`："二进宫"断言（第一次抛异常、第二次仍能调用），证明锁已释放
- [X] T014 [US3] 走查：未新增审计代码，定时触发经 `AgentService` 既有链路（`AgentScheduler` 无审计依赖）

---

## Phase 6: User Story 4 - 配置驱动、时区显式、非法配置不拖垮启动 (Priority: P2)

- [X] T015 [US4] 配置校验：缺 id / 空 cron / 非法 cron / 非法时区 / 消息空白 → 跳过该条 + ERROR（含 profile、序号、原始值）
- [X] T016 [US4] 未配时区 → 系统时区 + INFO 写明；Profile 内重复 id → 只注册第一条
- [X] T017 [P] [US4] `ProfileLoaderTest`：`schedules` 四字段解析 + `task` 兼容
- [X] T018 [US4] `AgentSchedulerTest`：非法配置与合法配置混排时只注册合法条；重复 id 只注册一次；`destroy` 取消 Future

---

## Phase 7: Polish & Cross-Cutting

- [X] T019 [P] `CoreAutoConfigurationTest`：断言 `TaskScheduler` 与 `AgentScheduler` Bean 存在
- [X] T020 [P] `application.yaml` 增加 `oryxos.scheduler.pool-size` 与 `schedules` 示例注释；
      `.oryxos/profiles/default.yaml` 增加注释版 `schedules` 示例
- [X] T021 同步文档：`docs/class/第25节` 补「附：仓库实现说明」（8 处差异）；`docs/TechnicalSolution.md` §8.4.1 新增定时触发实现说明
- [X] T022 使用 JDK 21 + 固定 Maven 3.9.9 执行 `mvn -pl oryxos-core -am test`，再执行仓库根 `mvn test`
- [X] T023 回填本文档完成状态与验证记录
- [ ] T024 按 `quickstart.md` 第 4 节完成真实到点验证（cron 链路只能真等一次）——**待使用方执行**：
      需要模型凭证的真实运行；单测已覆盖注册参数、防重叠、失败隔离与会话身份

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → 全部用户故事
- US1 依赖 Phase 2；US2 依赖 US1（`runOnce` 才有锁语义）
- US3 依赖 US2（失败隔离是锁语义的一部分）
- US4 依赖 Phase 2（注册期校验）

### 不可拆分的约束

**T006 与 T015 同属 `registerAll` 的"校验 + 注册"两步**：先校验后注册，避免"注册了一半才发现配置非法"。

### Parallel Opportunities

- T003 / T004 可并行（不同文件），T005 依赖 T004
- T009 / T011 / T013 / T018 同属 `AgentSchedulerTest`，**不得并行**（同一文件）
- T017（`ProfileLoaderTest`）与 T019（`CoreAutoConfigurationTest`）可并行

## 实现期决策（计划外）

| # | 决策 | 理由 |
|---|---|---|
| 1 | 占锁测试改用独立线程持锁（`CountDownLatch` 同步） | `ReentrantLock` 同线程可重入；首版在测试线程持锁，`tryLock` 直接成功，用例假绿/假红 |
| 2 | Mockito 用 `doReturn` 而非 `when(...).thenReturn` | `TaskScheduler.schedule` 返回 `ScheduledFuture<?>`，通配捕获类型在 `thenReturn` 下无法统一 |
| 3 | `runOnce` 设为 public | 文档 harness 的入口；也是将来"手动触发"的自然落点（本轮不加 API） |
| 4 | 注册失败（`schedule` 返回 null）按"未注册"处理并 ERROR | 调度器已关闭时 `schedule` 返回 null，不能当成注册成功计入条数 |
| 5 | 在 `AgentScheduler` 内做配置校验，不下沉到 `ProfileLoader` | 校验规则与"怎么用"强相关（锁键、消息回退），放消费方比放解析器更内聚；坏配置的隔离语义也与解析器一致（跳过 + 日志） |

## 验证记录

| 验证项 | 方式 | 结果 |
|---|---|---|
| core 模块回归 | `mvn -pl oryxos-core -am test`（JDK 21 + Maven 3.9.9） | **BUILD SUCCESS**；core 90 例全绿，其中 `AgentSchedulerTest` **15 例** |
| 全量回归 | 仓库根 `mvn test` | **10 个模块 BUILD SUCCESS**（core 90 / provider 24 / memory 43 / tool 113 / storage 14 / web 18 / channel-cli 2 / cli 20 / boot 5） |
| 时区语义 | 固定时钟断言 | `0 0 9 * * *` + `Asia/Shanghai` → 下次执行 `2026-01-01T01:00:00Z`（东八区 9 点） |
| 防重叠 | 独立线程持锁 + `verify(never())` | 跳过，`AgentService` 零调用；跨 Profile 同名 id 不受影响 |
| 失败隔离 | 抛异常后二次触发 | 不外抛；第二次仍能进入（锁已释放） |
| 配置健壮性 | 5 条非法 + 1 条合法混排 | 只注册合法 1 条；每条非法日志含 profile / 序号 / 原始值 |
| 审计 | 代码走查 | `AgentScheduler` 无审计依赖，全部经 `AgentService` |
| 真实到点触发 | 人工 | ⏳ 待使用方执行（T024） |

## Notes

- 不新增 Maven 模块、不新增第三方依赖（调度能力来自既有 spring-context）
- 不使用 `@Scheduled`（cron 写死）
- 不新增审计结构；定时触发经 `AgentService`，因此也不绕过 Sandbox
- 不做分布式协调 / 重试 / 任务管理 API / 热更新
- 未执行 Git commit（当前目录不是 git 仓库）
