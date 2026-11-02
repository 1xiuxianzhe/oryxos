# Research: 定时任务模块（AgentScheduler）

**Feature**: `008-scheduler` | **Date**: 2026-10-20

## R-01 调度机制：Spring `TaskScheduler` vs 其它

| 方案 | 说明 | 判定 |
|---|---|---|
| A. `@Scheduled(cron = "...")` | 注解写死 | ❌ cron 在编译期固定，改触发时间要改代码；且注解在 Bean 方法上，无法按 Profile 动态展开 |
| B. Spring `TaskScheduler.schedule(Runnable, Trigger)`（选定） | 运行期按配置逐条注册 `CronTrigger` | ✅ 配置驱动、标准 6 段 cron、自带时区参数；`ThreadPoolTaskScheduler` 在 spring-context 中 |
| C. Quartz | 功能全（持久化、集群、错过补偿） | ❌ 核心阶段用不上，新增依赖与一套表结构；与"薄调度层"定位冲突 |
| D. 自写线程 + 时间轮 | — | ❌ 重复造轮子，cron 解析、时区、DST 边界都要自己处理 |

**结论**：B。文档的判断与本仓库一致：只写"把配置接到调度能力上"的薄层。

## R-02 注册时机：`@PostConstruct` vs `InitializingBean`

core 只依赖 spring-context，没有 `jakarta.annotation-api`。同时注册必须发生在"Profile 已加载"之后：

- `ProfileLoader` 在其 `@Bean` 方法里**同步**调用 `loadAll()`，`ProfileRegistry` 是它的 `registry()`；
- `AgentScheduler` 依赖 `ProfileRegistry`，因此 Bean 初始化时 Profile 一定已就绪。

**结论**：实现 `InitializingBean.afterPropertiesSet()` 注册、`DisposableBean.destroy()` 取消。
不引入 `jakarta.annotation` 依赖，也不依赖 `SmartLifecycle` 的 phase 编排。

## R-03 锁键：只用 id 还是 `profileName#id`

文档写 `taskLocks.computeIfAbsent(sc.getId(), ...)`。但 `id` 是 Profile 内的局部标识：

- 两个 Agent 都写 `id: daily` 是**完全正常**的配置；
- 只用 id 会让两个不相关的任务共用一把锁 → 一个慢任务把另一个 Agent 的日报卡掉。

**结论**：键 = `profileName#id`。这不是"加保险"，而是"每个任务一把锁"语义的最小正确实现。
测试里用 `lockFor(profileName, id)` 直接取同一把锁验证。

## R-04 id 必填 vs 自动生成

| 方案 | 问题 |
|---|---|
| 自动生成 `profile#序号` | 条目顺序变化 → 锁键漂移；日志里"task-1"无法对应到具体哪条配置 |
| 自动生成 `profile#cron@message` | 长消息进日志与键，可读性差；改消息即换锁 |
| **id 必填（选定）** | 配置多一行，但锁键、注册键、日志定位三者一致 |

**结论**：必填。缺失 → 跳过 + ERROR（含 profile 与条目序号），符合"配置缺陷要可定位"。

## R-05 非法配置：跳过还是启动失败

- 启动失败：一条坏 cron 让整个 Agent OS 起不来。定时任务是**外围能力**，这个代价不成比例；
  且 `ProfileLoader` 的既有取向就是"坏文件跳过、不阻断其余 Profile"。
- 跳过 + ERROR（选定）：与既有取向一致；代价是必须靠日志发现，所以日志必须带
  profile、条目序号、原始 cron/时区值。

**结论**：跳过。但"跳过"必须显式可见，不能静默。

## R-06 时区：默认系统时区 vs 强制配置

- 强制必填：安全但增加配置负担，且绝大多数部署就是本机时区。
- 默认系统时区 + 启动日志写明（选定）：既不为难配置，又让"用的哪个时区"成为事实而非猜测。

测试上用固定时钟断言语义：cron `0 0 9 * * *`（每天 9 点）

| 时区 | 从 `2026-01-01T00:00:00Z` 起算的下一次执行 |
|---|---|
| `Asia/Shanghai` | `2026-01-01T01:00:00Z`（东八区 9 点） |
| `UTC` | `2026-01-01T09:00:00Z` |

两者相差 8 小时——这就是"时区真的被用上了"的证据，比断言 `toString()` 可靠。

## R-07 失败隔离的实现位置

文档在 `runOnce` 里 `catch (Exception e) { log.error(...) }`，且 `finally` 放锁。要点有三：

1. 异常不能漏到调度线程（否则 `TaskScheduler` 的 error handler 会记录，但任务锁可能已泄漏）；
2. 锁必须在 `finally`，且**测试要二次触发**才能证明锁真的放了（只断言"不抛异常"抓不到漏 unlock）；
3. 不重试、不降级——核心阶段只要求"不崩 + 留痕"。

**结论**：沿用文档结构；测试采用"二进宫"式断言（第二次触发仍能进入）。

## R-08 审计：为什么不新增代码

`AgentService.process(session, message)` 已经做了：`agent_runs` started/finished、
`ReActLoop` 内部 `llm_calls`、`ToolExecutor` 的 `tool_invocations`、会话持久化、`runId` MDC。

定时触发与人工触发的**唯一区别是会话三元组**（`scheduler/scheduler/profileName`）。
若为定时单开一套记录，就等于承认"两种触发源走两条账"——这与"第三种触发源、同一个入口"的定位直接矛盾。

**结论**：零审计代码。验收方式：代码走查 + 人工跑一次看 `agent_runs` 是否新增记录（quickstart 第 4 节）。

## R-09 线程池大小

调度线程只在"触发瞬间"占线程，真正的耗时在模型调用（同步阻塞）。

- 池大小 1：所有定时任务串行——一个慢任务会推迟其它任务的触发时刻；
- 池大小 2（选定）：不同任务互不排队，又不会在慢模型下堆出大量并发请求；
- 更大：核心阶段没有收益，反而放大并发模型调用。

**结论**：默认 2，可经 `oryxos.scheduler.pool-size` 调整。
