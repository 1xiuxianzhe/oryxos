# Tasks: 插件化 Agent (`class-29`)

> 顺序即依赖顺序。每个任务标注覆盖的 FR，完成后即跑对应测试。

## T1 · core 模型与校验（FR-003/004/006/006a，C-14）
- [x] `Profile` 新增 `agentDir`（`@JsonIgnore`）
- [x] 抽出 `ProfileValidator`：provider 段缺失 = 硬错；provider 名未声明 = 硬错；`ProfileLoader.validate` 委托它
- [x] `ProfileRegistry` 新增 `register/remove/exists`，旧方法委托
- [x] 跑 `oryxos-core` 全量测试（115 绿）

## T2 · core 目录加载（FR-001/002/003/005/007/008/009/011，US1）
- [x] `AgentDefinition`（frontmatter Map + 正文 + 资源路径）
- [x] `SkippedAgent(dirName, reason)`
- [x] `AgentLoader`：扫一级子目录、拆 frontmatter（BOM/未闭合/未知字段/`skills` 拒绝）、派生 Profile、注册、记 skipped
- [x] `AgentLoaderTest`(12) —— 覆盖合法/非法目录名/缺主文件/缺 provider/未声明 provider/`skills`/未知字段/未闭合/identity 对象/同名覆盖/最简形态/清单重置

## T3 · core 上下文（FR-015~020，US3）
- [x] `ContextLoader` 支持 agentDir：读正文（剥 frontmatter、无缓存）、跳过默认 bootstrap、显式 bootstrap 相对 agentDir、注入资源目录说明
- [x] `AgentContextInjectionTest`(8)

## T4 · core 调度（FR-012/013/038/039/040，US2/US6）
- [x] `AgentScheduler.registerProfile(Profile)`，`registerAll` 复用
- [x] 句柄表 `taskKey → ScheduledFuture`；`unregisterProfile(name)` 取消并清理三张表
- [x] `AgentSchedulerProfileRegistrationTest`(5)

## T5 · 装配时序（FR-010/011，US1-8）
- [x] `ProviderAutoConfiguration` 新增 `agentLoader` bean，`profileRegistry` `@DependsOn("agentLoader")`
- [x] 启动日志：目录 Agent N / legacy M / 跳过 K（逐条原因）
- [x] 真实启动冒烟：`daily-reconcile` + `weather-push` 均注册，定时任务 2 条（含分钟级），时序正确

## T6 · tool 边界（FR-021~028/034/034b，US4）
- [x] `SandboxAction` 增 `agentDir`（保留二参构造）
- [x] `WhitelistSandbox`：带 agentDir 时 shell 走"解释器白名单 + **`<agentDir>/scripts` 真实路径** + 拒绝元字符/`-c`/`-m`"，文件走"本目录"
- [x] `ShellTool`：退出码非 0 → `fail(不可重试)`（带输出尾部）、32KB 截断、输出并发读取（避免管道死锁）
- [x] `FileTool`：相对路径按 agentDir 解析、白名单收窄
- [x] `AgentScriptPolicyTest`(9) / `ShellToolAgentModeTest`(3) / `FileToolAgentModeTest`(2)

## T7 · memory（FR-029~033，US5）
- [x] `MemoryLocator`：按 `AgentContext` 解析路径 + 按路径缓存 `LongTermMemory`（保住写锁语义）
- [x] `DefaultMemoryService` 接 locator；`MemoryAutoConfiguration` 装配
- [x] `PerAgentMemoryTest`(4)

## T8 · 示例 Agent（A-016/017/018）
- [x] `.oryxos/agents/weather-push/AGENT.md`（每分钟，get_weather → notify；legacy schedule 已移除）
- [x] `.oryxos/agents/daily-reconcile/`：`AGENT.md` + `scripts/reconcile.py`（实跑输出 4 条差异）+ `skills/report-format.md` + `REFERENCE.md` + `samples/*.csv`

## T9 · 收口
- [x] 全仓 `mvn test`：**BUILD SUCCESS**（core 115 / memory 47 / tool 132 / storage 14 / web 61 / channel 2 / cli 20 / boot 13）
- [x] 信任边界：启动 WARN（带 scripts/ 的 Agent 数量 + 脚本不受内容限制的声明）
- [x] `quickstart.md`（目录结构、字段表、脚本边界、信任边界、验收清单）
- [x] 真模型端到端验收：取天气 → 写记忆 → webhook 推送（用户实测通过）

## 遗留（不阻断本节收口）

- [ ] `AgentScanFlowTest`：stub provider + 临时 SQLite 的自动集成测试；本节以"真实启动冒烟"替代
- [ ] `skippedAgents` 透出到 `GET /api/v1/profiles`（含 `source: agents|legacy`）与 CLI `profile list`：按约定留 30 节
