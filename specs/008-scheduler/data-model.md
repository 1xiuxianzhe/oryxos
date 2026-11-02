# Data Model: 定时任务模块（AgentScheduler）

**Feature**: `008-scheduler` | **Date**: 2026-10-20

本特性无持久化实体（不新增表、不改表结构）。"数据模型"指配置模型、键空间与运行期状态。

## 1. 配置模型（Profile 层）

```yaml
# .oryxos/profiles/daily-digest.yaml
schedules:
  - id: daily-digest            # 必填，Profile 内唯一
    cron: "0 0 9 * * *"         # 必填，Spring 6 段 cron（秒 分 时 日 月 周）
    timezone: Asia/Shanghai     # 可空；缺省用系统时区并在启动日志写明
    message: "汇总过去 24 小时的 PR 并推送摘要"   # 必填（可回退 task）
```

| 字段 | 类型 | 必填 | 默认 | 语义 |
|---|---|---|---|---|
| `id` | String | ✅ | — | Profile 内唯一标识；用于锁键、注册去重、日志定位 |
| `cron` | String | ✅ | — | Spring `CronTrigger` 表达式（6 段，含秒） |
| `timezone` | String | ❌ | 系统时区 | IANA 时区名（如 `Asia/Shanghai`）；非法 → 跳过该条 |
| `message` | String | ✅\* | — | 到点交给 Agent 的消息；\*`task` 可作兼容别名 |
| `task` | String | ❌ | — | **兼容旧字段**：`message` 为空时作为消息内容 |

**解析规则（唯一真相）**：

```text
message = (message 非空白) ? message : (task 非空白 ? task : 配置非法)
zone    = (timezone 非空白) ? ZoneId.of(timezone) : ZoneId.systemDefault()
key     = profileName + "#" + id
```

**字段兼容说明**：`ScheduleConfig` 原有 `{cron, task}` 两个字段，仓库内与默认 Profile 均未使用。
本轮新增 `id` / `timezone` / `message`，保留 `task` 作为别名（见 spec C-1 / A-005）。

## 2. 键空间与运行期状态

| 状态 | 结构 | 生命周期 | 说明 |
|---|---|---|---|
| 任务锁表 | `ConcurrentHashMap<String, Lock>` | 进程生命周期 | 键 = `profileName#id`；`ReentrantLock`；只增不减（任务数有限，且删除会破坏"同一任务同一把锁"的语义） |
| 已注册任务 | `Set<String>`（`ConcurrentHashMap.newKeySet()`） | 进程生命周期 | 键同上；用于启动日志与测试断言 |
| 已注册 Future | `CopyOnWriteArrayList<ScheduledFuture<?>>` | 进程生命周期 | `destroy()` 时逐个 `cancel(false)` |
| 调度线程池 | `ThreadPoolTaskScheduler` | Spring Bean | 线程名前缀 `oryxos-scheduler-`；池大小 `oryxos.scheduler.pool-size`（默认 2）；`waitForTasksToCompleteOnShutdown=false` |

## 3. 状态机（单次触发）

```text
        cron 到点
            │
            ▼
   tryLock(profileName#id)
      │            │
   拿不到         拿到
      │            │
   INFO 跳过       ├─ Session = getOrCreate("scheduler","scheduler",profileName)
   （结束）        ├─ AgentService.process(session, message)
                   │     ├─ 成功 → 既有审计（agent_runs / llm_calls / tool_invocations）
                   │     └─ 异常 → ERROR 日志（不重试、不外抛）
                   └─ finally unlock()
```

**不变式**：

1. 同一 `profileName#id` 同时最多一个执行中实例；
2. `unlock` 与 `tryLock` 必须成对，异常路径也不例外；
3. 触发路径上不存在"绕过 `AgentService`"的分支。

## 4. 会话身份

| 维度 | 值 | 理由 |
|---|---|---|
| channel | `scheduler` | 与 CLI / Web 区分的第三类触发源 |
| userId | `scheduler` | 没有人类用户；用固定值让同一 Profile 的历次触发复用同一会话 |
| profileName | 当前 Profile 名 | 会话归属某个 Agent |

**后果（显式记录）**：同一 Profile 的历史会持续累积，靠 `settings.max_history_turns`（默认 20 轮）截断兜底。
若将来需要"每次触发都是全新上下文"，应作为独立配置项（如 `fresh_session: true`）评估，不在本轮。

## 5. 与审计的关系

| 审计表 | 谁写入 | 本特性是否改动 |
|---|---|---|
| `agent_runs` | `AgentService`（started / finished） | 否 |
| `llm_calls` | `ProviderService`（经 `LlmCallAuditor`） | 否 |
| `tool_invocations` | `ToolExecutor`（经 `ToolInvocationAuditor`） | 否 |

定时触发与人工触发的审计差异**只有** session 的 channel/user 两个字段值（`scheduler`）。
不新增表、不新增字段——这是"第三种触发源，同一个入口"的直接体现。
