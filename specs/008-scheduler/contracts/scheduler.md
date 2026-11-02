# Contract: AgentScheduler

**Feature**: `008-scheduler` | **Version**: 1.0 | **Date**: 2026-10-20

## 1. 对外行为契约

| # | 条款 |
|---|---|
| 1.1 | 启动时 MUST 扫描所有 Profile 的 `schedules`，每条合法配置注册**恰好一个**任务 |
| 1.2 | 注册 MUST 使用 `CronTrigger(cron, zone)`；MUST NOT 使用 `@Scheduled` |
| 1.3 | 到点 MUST 调用 `AgentService.process(session, message)`；MUST NOT 自建处理链路、MUST NOT 直调 ReActLoop |
| 1.4 | 会话身份 MUST 固定 `("scheduler","scheduler",profileName)` |
| 1.5 | 消息 MUST 取 `message`，回退 `task` |
| 1.6 | 单条配置非法 MUST 只跳过该条，MUST NOT 阻断启动或影响其它条目 |
| 1.7 | 应用关闭 MUST 取消全部已注册任务 |

## 2. 并发契约

| # | 条款 |
|---|---|
| 2.1 | 同一 `profileName#id` MUST 同时最多一个执行中实例 |
| 2.2 | 拿不到锁 MUST 立即跳过（`tryLock` 语义），MUST NOT 阻塞等待、MUST NOT 排队 |
| 2.3 | 锁 MUST 在 `finally` 释放，异常路径同样 |
| 2.4 | 不同 `profileName` 的同名 `id` MUST NOT 互相阻塞 |

## 3. 失败契约

| # | 条款 |
|---|---|
| 3.1 | 单次执行抛出的任何异常 MUST NOT 冒到调度线程；MUST 记 ERROR（含任务键与原因） |
| 3.2 | 失败 MUST NOT 触发重试、MUST NOT 影响其它任务的后续触发 |
| 3.3 | 失败与成功的审计 MUST 由既有 `AgentService` 链路完成，MUST NOT 新增定时专用结构 |

## 4. 配置契约

| 键 | 默认 | 契约 |
|---|---|---|
| `schedules[].id` | 无 | 必填；Profile 内重复时只取第一条，其余 ERROR 跳过 |
| `schedules[].cron` | 无 | 必填；非法表达式跳过并 ERROR（含原始值） |
| `schedules[].timezone` | 系统时区 | IANA 名称；非法跳过并 ERROR；缺省时启动日志 MUST 写明实际使用值 |
| `schedules[].message` | 无 | `message` 与 `task` 至少一个非空白，否则跳过并 ERROR |
| `oryxos.scheduler.pool-size` | `2` | 调度线程池大小；小于 1 时按 1 处理 |

## 5. Required Verification

| # | 验证项 | 落点 |
|---|---|---|
| 1 | 注册时 `CronTrigger` 带配置的 cron，且时区语义正确（固定时钟下东八区 9 点 = 01:00Z） | `AgentSchedulerTest` |
| 2 | 未配时区时使用系统时区，且日志写明 | `AgentSchedulerTest` |
| 3 | 无 `schedules` → 注册 0 条、不报错 | `AgentSchedulerTest` |
| 4 | 锁被占 → 本次跳过，`AgentService` 零调用 | `AgentSchedulerTest` |
| 5 | 不同 Profile 同名 id 互不阻塞 | `AgentSchedulerTest` |
| 6 | `runOnce` 内抛异常 → 不外抛；二次触发仍能进入（锁已释放） | `AgentSchedulerTest` |
| 7 | 会话三元组固定，两次触发同一 Session | `AgentSchedulerTest` |
| 8 | `message` 优先、`task` 回退 | `AgentSchedulerTest`、`ProfileLoaderTest` |
| 9 | 非法 cron / 非法时区 / 缺 id / 消息空白 → 跳过该条且其余照常注册 | `AgentSchedulerTest` |
| 10 | Profile 内重复 id → 只注册第一条 | `AgentSchedulerTest` |
| 11 | `destroy()` 取消全部已注册 Future | `AgentSchedulerTest` |
| 12 | `schedules` 四字段能被 ProfileLoader 正确解析（含 `task` 兼容） | `ProfileLoaderTest` |
| 13 | `CoreAutoConfiguration` 装配出 `TaskScheduler` 与 `AgentScheduler` | `CoreAutoConfigurationTest` |
| 14 | 全仓 `mvn test` 通过 | 仓库根命令 |
