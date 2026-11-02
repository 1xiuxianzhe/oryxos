# Quickstart: 定时任务模块（AgentScheduler）

**Feature**: `008-scheduler`

## 1. 它解决什么问题

到点了，系统自己拼一条消息交给 `AgentService`——与 `oryxos chat`、Web API 走的是**同一个入口**。
日报、周报、巡检告警这类"没人催也得干"的 Agent，靠它才成立。

**边界**：它只是触发源。消息说什么归 Profile / SKILL.md，怎么想归 ReActLoop，怎么动手归 Tool 体系。
它不做工作流编排、不做分布式协调、不做任务管理 API、不重试、不告警。

## 2. 配置

在 Profile 里加 `schedules`（`.oryxos/profiles/<name>.yaml`）：

```yaml
schedules:
  - id: morning-brief              # 必填，Profile 内唯一（锁键与日志定位都靠它）
    cron: "0 0 9 * * *"            # 秒 分 时 日 月 周 —— 每天 09:00
    timezone: Asia/Shanghai        # 可空；不写就用系统时区，并在启动日志写明
    message: "汇总过去 24 小时的 PR 并推送摘要"
```

调度线程池（全局，`application.yaml`）：

```yaml
oryxos:
  scheduler:
    pool-size: 2        # 默认 2；小于 1 按 1 处理
```

### 2.1 三条规则

1. **`id` 必填**：它是防重叠锁与注册去重的键。同一 Profile 里重复的 `id` 只认第一条。
2. **时区不写不报错，但会写日志**：启动日志会打印实际使用的时区，避免"以为配的是东八区"。
3. **一条坏配置只影响它自己**：非法 cron / 时区 / 缺消息 → 该条 ERROR 跳过，应用照常启动。

## 3. 行为速查

| 场景 | 结果 |
|---|---|
| 正常到点 | `AgentService.process(session, message)` 执行一次，审计表新增记录 |
| 上一次还没跑完 | INFO 跳过本次触发，**不排队** |
| 两个 Profile 都有 `id: daily` | 互不阻塞（锁键含 profileName） |
| 执行抛异常 | ERROR 日志；锁释放；下次触发照常 |
| 应用关闭 | 已注册任务全部取消 |

## 4. 怎么验（人工）

```powershell
# 0) 环境（必须 JDK 21 + 固定 Maven）
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\ms-21.0.12"
$mvn = "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd"

# 1) 模块回归（调度逻辑全在 core）
& $mvn -f g:\code\ORYXOS\pom.xml -pl oryxos-core -am test

# 2) 全量回归
& $mvn -f g:\code\ORYXOS\pom.xml test
```

真实到点验证（cron 链路只能真等一次）：

1. 把 `cron` 设为 `0 * * * * *`（每分钟整点秒触发），`message` 设一句无副作用的话。
2. 启动应用，观察日志出现 `[Scheduler] 已注册定时任务: ...`，随后每分钟出现一次处理日志。
3. 查 `agent_runs`：应新增一条记录，`channel=scheduler`、`user_id=scheduler`、`profile_name=<profile>`；
   `llm_calls` / `tool_invocations` 按该次处理实际发生情况写入。
4. 改 cron 后**重启**（本轮不支持热更新），观察按新时间触发——这就是"配置驱动"的体感验证。

## 5. 已知边界与代价

- **单实例假设**：进程内锁只在一个 JVM 内有效。多实例会各自触发——归属协调属扩展阶段。
- **不热更新**：改配置需重启。
- **不重试**：一次失败就是一次失败，不自动补跑。
- **会话是复用的**：同一 Profile 的历次触发共享 `("scheduler","scheduler",profileName)` 会话，
  历史靠 `max_history_turns` 截断；需要"每次全新上下文"时再评估 `fresh_session` 配置。
- **不做任务管理**：没有"立即执行一次"的接口或命令（`runOnce` 已 public，接入 API 是后续增量）。
