# Implementation Plan: 插件化 Agent

**Feature**: `class-29` | **Spec**: `specs/010-plugin-agent/spec.md` | **Created**: 2026-10-28

## 1. 技术路线

一句话：**新增一条"目录 → Profile"的来源，其余全部复用既有底座**。不改 `ReActLoop` / `ToolExecutor` / `AgentService` 的控制流。

```
.oryxos/agents/<name>/AGENT.md
        │  AgentLoader（解析 frontmatter + 正文 + 资源路径）
        ▼
   AgentDefinition ── deriveProfile() ──► Profile（含 agentDir）
        │                                      │
        ├─ 校验（与 legacy 同一个 ProfileValidator）
        ▼                                      ▼
  ProfileRegistry.register(profile)      ContextLoader 读正文 → system prompt
        │
        ▼
  AgentScheduler.registerProfile → schedules → 到点 agentService.process(...)
```

## 2. 分层改动

| 层 | 文件 | 改动 |
|---|---|---|
| core 模型 | `Profile` | 新增 `agentDir`（`@JsonIgnore`，YAML 手写不生效） |
| core 校验 | `ProfileValidator`（新） | 从 `ProfileLoader.validate` 抽出；**缺 provider 段改为硬错**，legacy 同步 |
| core 加载 | `AgentLoader` / `AgentDefinition` / `SkippedAgent`（新） | 扫目录、拆 frontmatter、派生、注册、记跳过原因 |
| core 注册表 | `ProfileRegistry` | 新增 `register/remove/exists`；旧方法委托 |
| core 上下文 | `ContextLoader` | `agentDir != null` 时读 AGENT.md 正文（剥 frontmatter、无缓存）、不读默认三件套、注入资源目录说明 |
| core 调度 | `AgentScheduler` | 抽 `registerProfile`、新增 `unregisterProfile`、句柄表改为 `taskKey → future` |
| tool 沙箱 | `SandboxAction` / `WhitelistSandbox` | `SandboxAction` 增 `agentDir`（保留二参构造）；带 agentDir 时 shell 走"解释器 + 本目录 scripts"、文件走"本目录" |
| tool 内置 | `ShellTool` / `FileTool` | 退出码、32KB 截断、相对路径按 agentDir 解析 |
| memory | `MemoryLocator`（新）+ `DefaultMemoryService` | 按 `AgentContext` 解析 `<agentDir>/memory/MEMORY.md`；按路径缓存实例（保住写锁语义） |
| provider 装配 | `ProviderAutoConfiguration` | 新增 `agentLoader` bean（扫 `agents/`），`profileRegistry` bean `@DependsOn` 它，保证早于 `AgentScheduler` |

## 3. 启动时序（关键不变式）

```
providerModelRegistry → profileLoader(profiles/) → agentLoader(agents/) → profileRegistry → agentScheduler.registerAll()
```

- 扫描必须完成后再暴露 `ProfileRegistry`，否则 `AgentScheduler.afterPropertiesSet` 看不到目录 Agent 的 `schedules`。
- 用同一 `Bean` 依赖（`@DependsOn`）保证顺序，**不引入新的生命周期钩子**。

## 4. 兼容性影响（必须显式承担）

| 变更 | 影响 | 处置 |
|---|---|---|
| 缺 `provider` 段由"WARN 仍注册"改为"硬错跳过" | legacy `profiles/*.yaml` 若缺 provider 将不再加载 | 启动日志点名；plan 已记录，属按 spec A-021 的**有意收紧** |
| `shell` 在带 `agentDir` 时不再允许任意白名单命令 | 目录 Agent 只能跑自己 `scripts/` 下的脚本 | legacy 无 `agentDir`，行为不变 |
| `read_file` 在带 `agentDir` 时收窄到本目录 | 目录 Agent 读不到工作区其它文件 | 由 `MemoryService` 单独提供记忆读写，不经 `read_file` |
| `Profile` 新增字段 | Jackson 反序列化需忽略该字段 | `@JsonIgnore` |

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 扫描晚于调度器 → 定时不生效 | 用 Bean 依赖强制时序 + 启动日志打印"目录 Agent N / legacy M / 跳过 K" |
| 注销后定时仍在跑 | `unregisterProfile` 必须 cancel 全部 future 并从三张内存表移除；测试断言"超过一个周期触发 0 次" |
| 相对路径基准不清 → 读不到资源 | 双重保障：prompt 注入 agentDir 绝对路径 + 工具层按 agentDir 兜底 |
| shell 命令拼接绕过 | 带 agentDir 时拒绝 shell 元字符与 `-c/-m/-` 形态；路径用真实路径判定（防 symlink） |
| 每 Agent 记忆实例重复创建导致写锁失效 | `MemoryLocator` 按路径缓存 `LongTermMemory` |
| 未知 frontmatter 字段静默忽略 | `FAIL_ON_UNKNOWN_PROPERTIES=true`，报错点名字段 |

## 6. 验证策略

- **单测**：`AgentLoaderTest`、`DeriveProfileTest`、`ProgressiveDisclosureTest`、`ScriptPolicyTest`、`PerAgentMemoryTest`、`AgentSchedulerRegisterTest`
- **集成**：`AgentScanFlowTest`（stub provider + 临时 SQLite：目录 Agent → 触发 → 审计）
- **回归**：全仓 `mvn test`；重点看 `ProfileLoaderTest` / `ToolAutoConfigurationTest` / `SchedulerFlowIT` 不变
- **人工**：`weather-push` + `deepseek-flash` + `tools/webhook_sink.py:9099`

## 7. 实现结果与偏差（2026-10-30）

| 项 | 计划 | 实际 |
| --- | --- | --- |
| 运行时注销 | 只做 `registerProfile`，`unregister` 留 30 节 | **提前到本节**：`unregisterProfile` 会取消该 Profile 全部 future 并清理三张内存表（否则"删了还在跑"） |
| 缺 `provider` | 与 legacy 保持 WARN | **两条来源统一收紧为硬错**（含 legacy，属有意行为变更） |
| 集成测试 | `AgentScanFlowTest` | 未写；以"真实启动冒烟"替代（确认扫描早于调度注册、2 个 Agent 2 条任务） |
| 文档 | 无 | 补 `quickstart.md`；信任边界同时进启动 WARN 与文档 |
| skipped 可见性 | core 侧数据 | core 侧已完成，Web/CLI 透出按约定留 30 节 |
