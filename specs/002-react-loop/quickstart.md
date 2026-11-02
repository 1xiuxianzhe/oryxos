# Quickstart Validation: ReAct 循环

**Feature**: `002-react-loop` | **Date**: 2026-09-16

本文件是**验证指南**：如何证明本特性按 spec 工作。实现细节见 [plan.md](./plan.md)、接口见 [contracts/](./contracts/)、数据见 [data-model.md](./data-model.md)。

---

## 0. 前置条件

| 项 | 要求 |
|----|------|
| JDK | 21（`JAVA_HOME` 指向 JDK 21） |
| Maven | 3.9+ |
| 网络 | 单测**不需要**；仅"真实模型验证"一节需要 |
| 环境变量 | 仅真实模型验证需要：`DEEPSEEK_API_KEY`（Windows 用 `set DEEPSEEK_API_KEY=xxx`） |
| 工作区 | `.oryxos/`（含 `profiles/default.yaml`；能力一已建立） |

---

## 1. 一键跑通 harness（主验证，无需网络）

```bash
mvn test
```

**期望**：新增的 5 个 harness 测试类 + 3 个存储侧测试全部通过（全量 91 个测试）；harness 单测总时长 < 30 秒、零网络依赖（SC-006）。

| 测试类 | 覆盖的验收点 |
|--------|--------------|
| `ReActLoopTest` | 无工具调用一轮收尾；有工具调用则执行并回填进下一轮；转满最大轮数强制停（坑一/SC-002）；每轮响应与工具结果都累积进会话（坑三/FR-006）；超时抛 `ProcessingTimeoutException` |
| `PromptBuilderTest` | 四部分顺序正确（FR-011）；历史超 N 次往返被截断且工具消息不被拦腰截断（FR-012）；system prompt 末尾含当前日期时间（FR-013） |
| `ToolExecutorTest` | 成功写审计 `success=true`；失败也写 `success=false` 带原因且**不吞异常**；审计写入失败不阻断主流程（FR-020）；工具未注册/参数非法均降级为失败结果 |
| `AgentServiceTest` | 处理期间 `AgentContext.current()` 可取到当前 Profile；**处理抛异常时 `finally` 也清掉**（FR-010）；结束后会话被持久化（FR-009）；Profile 不存在时给出可读错误 |
| `ContextLoaderTest` | 改文件后下一次 `build` 立即读到新内容（无缓存/FR-014）；显式引用的 SKILL 缺失报错、默认 Bootstrap 缺失只 WARN（FR-015） |
| `ToolInvocationRepositoryTest` | 手工脚本建出的 `tool_invocations` 能存能读，`success` / `error_message` 两列真实存在 |
| `JpaSessionStoreTest` | 会话元数据 + 完整历史（含顺序）落库，`flush + clear`（模拟重启）后能读回并可接续追加（SC-004） |
| `AgentRunRepositoryTest` | 请求级审计两阶段写：开始即记下"问的是什么 / 哪个模型"，收尾落终态与 token 汇总；明细按 `run_id` 关联；缺 started 记录时补写不丢（SC-011） |
| `CoreAutoConfigurationTest` | core 引擎层 Bean 齐备；缺 `LlmClient` / `ProfileRegistry` 时启动失败且错误可读 |

**失败排查**
- `no such column: success` → 库是旧结构；删除 `.oryxos/oryxos.db` 或执行 `db/migration/` 下的脚本。
- `Error parsing time stamp` → 时间列为 TEXT 亲和性；同上，删除重建。
- 断言"轮数恰好 10"失败 → 检查停止判定是否**先看工具调用、再看文本**（D-05）。

---

## 2. 逐个场景人工确认（可选，用于 code review 佐证）

| 场景 | 期望观察 |
|------|----------|
| 死循环兜底 | mock 模型每轮都返回工具调用 → 恰好调用模型 10 次，返回文本含"已达到最大轮数" |
| 上下文截断 | 造 30 次往返历史 + 1 次工具调用 → 发送给模型的历史只含最近 20 段，且每段完整 |
| 即改即生效 | 修改 `.oryxos/skills/<name>/SKILL.md` → 下一次组装立刻包含新内容（无重启） |
| 缺文件策略 | 把 `bootstrap.files` 指向不存在的文件 → 报错；删掉默认 `SOUL.md` → 仅 WARN 且能继续 |
| 审计失败不阻断 | mock 审计器抛异常 → 工具结果照常返回，日志出现 ERROR |
| 上下文清理 | 让循环抛异常 → `AgentContext.current()` 为 null |
| 会话重启读回 | 落库后清空一级缓存再读回 → 元数据与消息顺序一致，且能接着追加（SC-004） |
| 结构化日志 | 一次处理结束后，仅凭日志即可看出轮数、工具调用、耗时与停止原因；日志行上的 `runId` 与 `agent_runs` 里那条记录一致（SC-010） |
| 请求级审计 | 让成功、达轮数上限、超时、模型报错各跑一次 → `agent_runs` 每次都有记录，含请求内容、模型、token、轮数、工具次数与停止原因（SC-011） |

---

## 3. 真实模型验证（Demo 一，手动运行，默认跳过）

目的：证明"多步任务一次办成"在真实模型下成立（US1、SC-001），且自实现循环确实在调度工具。

**前置**
- 已设置 `DEEPSEEK_API_KEY`；
- `.oryxos/profiles/default.yaml` 的 provider 可用（能力一已验证 `deepseek` + `deepseek-flash`）。

**运行**

```bash
mvn -pl oryxos-boot -am test -Dtest=ReactLoopSmokeIT -Dsurefire.failIfNoSpecifiedTests=false
```

**落库到应用库（可选）**：`ReactLoopSmokeIT` 写的是独立测试库 `oryxos-boot/target/oryxos-react-loop-smoke.db`。
若要在**运行中的应用所使用**的 `.oryxos/oryxos.db` 里直接查到记录：

```bash
mvn -pl oryxos-boot -am test -Dtest=ReactLoopRealRunIT -Dsurefire.failIfNoSpecifiedTests=false
```

`ReactLoopRealRunIT` 不覆盖数据源（与应用的 `application.yaml` 完全一致），跑完会打印真实库文件路径，
并把本次的 `llm_calls` 与 `tool_invocations` 逐条打印出来；也可用 `-Doryxos.realrun.workspace=D:/xxx/.oryxos` 指定别的库。

**该用例做什么**
1. 注册一个**本地假工具**（例如 `get_weather(city)`，返回固定天气 JSON），使验证不依赖外部 HTTP 与域名白名单；
2. 用真实 Provider 驱动 `ReActLoop`，提问"北京今天天气怎么样，我该穿什么？"；
3. 断言：模型确实请求了该工具 → 工具被 `ToolExecutor` 执行一次 → 会话中同时存在 `assistant`（含工具请求）与 `tool`（工具结果）消息 → 最终答复不含工具请求。

**期望**
- 用例通过，日志中可见 `[ReAct]` 处理开始/结束与工具调用记录；
- `tool_invocations` 表新增一条 `success=1` 的记录（可手工查询确认）。

**失败排查**
- 401 → `DEEPSEEK_API_KEY` 未设置或无效（能力一已提供启动阶段校验）；
- 用例超时 → 检查 `settings.processing_timeout_seconds` 是否被设得过小。

---

## 4. 端到端人工验收（可选）

用项目自带 CLI 走一遍完整链路（若 CLI 命令尚未接入本能力，则以第 3 节的手动用例为准）：

```bash
# 设置密钥后
oryxos chat --profile default --message "看看今天北京天气，帮我决定穿什么"
```

**期望**：Agent 不是直接编造天气，而是先调用工具、再依据结果给出建议；会话历史与服务端日志都能还原这次处理。
