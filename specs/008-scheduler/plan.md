# Implementation Plan: 定时任务模块（AgentScheduler）

**Branch**: `008-scheduler` | **Date**: 2026-10-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-scheduler/spec.md`

## Summary

薄薄一层：把 Profile 里的 `schedules` 接到 Spring 的调度能力上，到点调 `AgentService`。四件事：

1. **补配置**：`ScheduleConfig` 扩到 `id / cron / timezone / message`，`task` 保留为兼容别名。
2. **写调度层**：`AgentScheduler`（`registerAll` + `runOnce` + `profileName#id` 的锁表），
   实现 `InitializingBean`（注册）/ `DisposableBean`（取消）。
3. **装配**：`CoreAutoConfiguration` 产出 `ThreadPoolTaskScheduler`（`oryxos.scheduler.pool-size`，默认 2）
   与 `AgentScheduler`；调度层只依赖 `TaskScheduler` 抽象。
4. **验收 harness**：不真等时间——注册参数用 mock 抓、触发逻辑直接调 `runOnce`。

**不做什么**：不做任务编排/工作流、不做分布式协调、不做任务管理 API、不做失败重试与告警、
不新增审计结构、不新增 Maven 模块。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）

**Primary Dependencies**: 无新增。调度能力来自既有 `spring-context`（`TaskScheduler` / `ThreadPoolTaskScheduler` /
`CronTrigger`，实测版本 6.2.3），日志用既有 SLF4J

**Storage**: 无变更（定时触发走 `AgentService`，审计沿用 `agent_runs` / `llm_calls` / `tool_invocations`）

**Testing**: JUnit 5 + Mockito + AssertJ；`ApplicationContextRunner`（core 已有 `spring-boot-starter-test` test 依赖）

**Target Platform**: JDK 21；单实例部署（核心阶段不做多实例归属）

**Project Type**: Maven 多模块单体（后端）

**Constraints**:

- 只改 `oryxos-core`（宪法「9 模块固定」；调度是引擎的第三种入口，不新增模块）
- 不引入 Spring Boot 依赖到 core 主代码（`@Value` 属 spring-context/beans，`@ConfigurationProperties` 不用）
- 不使用 `@Scheduled`（cron 写死在代码里）
- 不引入分布式锁 / 重试框架 / 任务队列

**Scale/Scope**: 新增生产类 1 个、扩展 1 个配置类、改 1 个装配类；新增测试类 1 个、扩展 2 个已有测试类。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 / 约束 | 判定 | 说明 |
|---|---|---|---|
| I | Java 21 + Spring Boot 3.x 单体、9 模块 | ✅ 通过 | 不新增模块，调度层落在 `oryxos-core` |
| II | 自实现 ReAct 循环 | ✅ 通过 | 调度层只调 `AgentService`，不碰循环内部 |
| III | Spring AI 边界控制 | ✅ 通过 | 不涉及 Spring AI |
| IV | Tool 体系统一管理 | ✅ 通过 | 不触碰工具模块 |
| V | Memory 三层统一门面 | ✅ 通过 | 不触碰 Memory |
| VI | 审计表 day one 落库 | ✅ 通过 | 定时触发复用既有审计链路，不新增结构（FR-016） |
| VII | 核心阶段范围控制 | ✅ 通过 | 不做分布式协调、重试、任务编排；无 SSE / 认证 / 并行 |
| 约束 4 | Sandbox 白名单 | ✅ 通过 | 定时触发经同一 `AgentService`，工具调用照常过 `Sandbox` |
| 约束 5 | SQLite 表结构手动维护 | ✅ 通过 | 无表结构变更 |

## Project Structure

### Documentation (this feature)

```text
specs/008-scheduler/
├── spec.md              # 规格（含冲突收敛 C-1 ~ C-8）
├── plan.md              # 本文件
├── research.md          # Phase 0：备选方案与取舍
├── data-model.md        # Phase 1：配置模型、状态与键空间
├── quickstart.md        # Phase 1：怎么配、怎么验
├── contracts/
│   └── scheduler.md     # Phase 1：契约 + 验证项清单
├── checklists/
│   └── requirements.md  # 规格质量自检
└── tasks.md             # Phase 2：任务拆解与完成状态
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── schedule/
│   └── AgentScheduler.java        # 新增：注册 + runOnce + 锁表 + 生命周期
├── profile/
│   └── Profile.java               # 改：ScheduleConfig 扩字段（id / cron / timezone / message，保留 task）
└── config/
    └── CoreAutoConfiguration.java # 改：TaskScheduler Bean + AgentScheduler Bean

oryxos-core/src/test/java/io/oryxos/core/
├── schedule/AgentSchedulerTest.java # 新增：注册参数、防重叠、失败隔离、会话身份、配置校验
├── profile/ProfileLoaderTest.java   # 改：schedules 四字段解析 + task 兼容
└── config/CoreAutoConfigurationTest.java # 改：调度器 Bean 装配断言

oryxos-boot/src/main/resources/application.yaml  # 改：oryxos.scheduler.pool-size 与 schedules 示例注释
.oryxos/profiles/default.yaml                    # 改（可选）：注释形式的 schedules 示例
```

**Structure Decision**: 全部落在 `oryxos-core`。调度是"触发源"，与 `AgentService` 同层，
依赖方向天然向下（不反向依赖 provider / tool），符合既有模块边界。

## 实现决策记录

| # | 决策 | 理由 / 代价 |
|---|---|---|
| **D-01** | 调度落在 `oryxos-core`，不新增模块 | 宪法「9 模块固定」；调度只依赖 core 内已有类型（ProfileRegistry / SessionManager / AgentService）+ spring-context |
| **D-02** | 用 `InitializingBean` / `DisposableBean`，不用 `@PostConstruct` | core 无 `jakarta.annotation` 依赖；且注册必须在 Profile 加载完成后，Bean 初始化期正好满足 |
| **D-03** | `AgentScheduler` 依赖 `TaskScheduler` 抽象而非 `ThreadPoolTaskScheduler` 具体类 | 测试可用 mock 抓注册参数，不必真起线程池；实现可替换（Spring 自带多种调度器） |
| **D-04** | 锁键 `profileName#id` | 文档只用 id，跨 Profile 同名任务会互相阻塞；加 profileName 才是"每个任务一把锁" |
| **D-05** | `id` 必填、Profile 内重复只取第一条 | 锁键与注册键必须稳定可预测；自动生成 id 会随条目顺序漂移（见 spec C-2 / A-002） |
| **D-06** | 单条非法配置 → ERROR + 跳过 | 与"失败隔离"同一取向：一条坏配置不该让整个应用起不来；代价是必须靠日志发现，故日志含 profile/序号/原始值 |
| **D-07** | 未配时区 → 系统时区 + 启动日志写明 | 让"用的哪个时区"成为可观测事实，而不是隐含默认 |
| **D-08** | `runOnce` 设为 public | 既是文档 harness 的测试入口，也是将来"手动触发"能力的自然落点（本轮不加 API） |
| **D-09** | 不新增审计代码 | `AgentService` 已有 `agent_runs` 与下游明细审计；新增一条定时专用路径等于第二套账 |
| **D-10** | 线程池默认 2 | 调度任务的瓶颈在模型调用而非线程；默认 2 足以让不同任务不互相排队，又不会在慢模型下堆出大量并发请求 |

## Complexity Tracking

> 仅记录需要论证的"偏离"，其余均为直接沿用既有设计。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 在 `oryxos-core` 增加调度相关代码（而非独立模块） | 宪法固定 9 模块；调度是引擎的第三种入口，与 `AgentService` 同层 | 新建 `oryxos-schedule` 模块：违反模块约束，且它只依赖 core，独立出去没有复用价值 |
| `ScheduleConfig.id` 必填（相比文档的"直接用 id"更严格） | 锁键/注册键/日志定位必须一致且稳定 | 自动生成 id（`profile#序号`）：条目顺序一变，锁键与日志就漂移；排障时对不上"上一次是哪条" |
| 单条非法配置不阻断启动 | 定时任务是外围能力，一条坏 cron 不该让整个 Agent OS 起不来 | 启动即失败：与 ProfileLoader"坏文件跳过"的既有取向冲突，且会让一个 Agent 的配置错误影响全部 Agent |

## Phase 0 / Phase 1 输出

- Phase 0 研究结论见 [research.md](./research.md)
- Phase 1 配置与状态模型见 [data-model.md](./data-model.md)
- Phase 1 契约见 [contracts/scheduler.md](./contracts/scheduler.md)
- Phase 1 验收指引见 [quickstart.md](./quickstart.md)
