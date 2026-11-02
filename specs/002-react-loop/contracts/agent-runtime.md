# Contract: Agent 运行时模块接口（oryxos-core）

**Feature**: `002-react-loop` | **Date**: 2026-09-16

本文件是本特性对外（对后续能力与调用入口）稳定的接口与错误契约。所有类型位于 `io.oryxos.core` 下。

---

## 1. ReActLoop（循环调度）

```java
public final class ReActLoop {
    public ReActLoop(LlmClient llmClient,
                     ToolRegistry toolRegistry,
                     PromptBuilder promptBuilder,
                     ToolExecutor toolExecutor);

    /** 驱动一次完整处理，返回最终答复文本。 */
    public String run(String sessionId, Session session, String userMessage, Profile profile);
}
```

**行为契约（顺序不可交换）**

1. 先把 `userMessage` 以 `role=user` 追加进 `session`。
2. 每轮：`promptBuilder.build(...)` → `llmClient.call(sessionId, profile, request)` → **先**把响应追加进 `session` → **再**判断是否含工具调用。
3. 含工具调用 → `toolExecutor.executeAll(sessionId, calls)`，逐个结果回填到 `session`，进入下一轮。
4. 不含工具调用且文本非空 → 返回文本（`FINAL_ANSWER`）。
5. 不含工具调用且文本为空 → 返回明确的无效响应提示（`EMPTY_RESPONSE`）。
6. 达到 `profile.maxIterations()` → 返回"已达到最大轮数（N），已停止"（`MAX_ITERATIONS`）。
7. 超过 `profile.processingTimeoutSeconds()` → 抛 `ProcessingTimeoutException`（`TIMEOUT`）。
8. 模型调用异常 → 原样上抛（`MODEL_ERROR`），不吞、不改为兜底文本。

**不变量**
- 每轮的模型响应与每条工具结果都必须进入 `session`（审计前提，spec 坑三）。
- 工具结果回填后才进入下一轮；同一轮内多个工具按顺序执行。
- 不缓存输入组装结果（每次循环都重新组装）。

**日志契约**：处理开始/结束（INFO，含 sessionId、profile 名、停止原因、轮数、总耗时）；每轮迭代（DEBUG，轮次序号 + 是否含工具调用）。

---

## 2. PromptBuilder（输入组装）

```java
public final class PromptBuilder {
    public PromptBuilder(ContextLoader contextLoader, MemoryReader memoryReader);

    public LlmRequest build(Session session, Profile profile, List<OryxTool> tools);
}
```

**顺序契约**（FR-011）
1. `systemPrompt` 文本 = `identity` → Bootstrap 文件内容 → SKILL 文件内容 → 当前日期时间（`yyyy-MM-dd HH:mm:ss z`）。
2. 长期记忆：非空时作为**独立**的 system 消息，紧随主 system 消息之后；为空则不产生消息。
3. 会话历史：最近 `maxHistoryTurns` 次**完整往返**（以 `role=user` 为边界切分，末段未闭合也计入）。

**截断契约**：截断只作用于历史部分；`systemPrompt` 与长期记忆不参与截断。

**错误契约**：显式引用的 Bootstrap / SKILL 文件缺失 → 抛 `ContextLoadException`（不静默跳过）。

**无缓存契约**：每次 `build` 都重新读取 Bootstrap / SKILL 文件，任何缓存都会违反 FR-014。

---

## 3. ToolExecutor（工具执行）

```java
public final class ToolExecutor {
    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInvocationValidator validator,
                        ToolInvocationAuditor auditor);

    public ToolResult execute(String sessionId, LlmClient.ToolCall call);

    public List<ToolResult> executeAll(String sessionId, List<LlmClient.ToolCall> calls);
}
```

**顺序契约（固定）**：查工具表 → 前置校验（`validator.validate`）→ 解析参数 → 执行 → 写审计。

**错误契约**
| 情形 | 行为 |
|------|------|
| 工具未注册 | 返回失败结果（`success=false`，说明"未找到工具"），不抛异常 |
| 参数不是合法 JSON | 降级为原样参数，不抛异常 |
| 前置校验失败（沙箱等） | 返回失败结果（含校验原因），不抛异常 |
| 工具内部抛异常 | 捕获为失败结果（含原因），**不向上抛**，循环继续 |
| 审计写入失败 | 记 ERROR 日志后**继续**返回工具结果（FR-020） |

**审计契约**：成功与失败**都**写一条 `tool_invocations`，携带 `sessionId`、当前 Agent 配置名、工具名、入参、结果/错误原因、耗时。

**顺序执行契约**：`executeAll` 严格按列表顺序执行（核心阶段不做并行）。

---

## 4. AgentService（处理编排入口）

```java
public final class AgentService {
    public AgentService(ProfileRegistry profiles, SessionManager sessions, ReActLoop reActLoop);

    /** 三种触发源（CLI / Web / 定时）统一的处理入口。 */
    public String process(String sessionId, String profileName, String channel,
                          String userId, String userMessage);
}
```

**契约**
1. 解析 Profile：`profiles.getProfile(profileName)`；不存在 → 抛 `AgentConfigurationException`（信息含 profile 名，不得返回空答案）。
2. `sessionId` 为空则新建会话；否则复用（内存未命中时从存储读回）。
3. `AgentContext.set(profile)` → `try` 调用 `reActLoop.run(...)` → `finally` **必须** `AgentContext.clear()`。
4. 处理成功返回前，`sessions.save(session)` 持久化会话历史（FR-009）。
5. 异常路径：`AgentContext` 仍须清理；错误按 FR-018 上抛，不吞。

**不变量**：一次处理结束后（无论成功或异常），同一线程上 `AgentContext.current() == null`。

**日志契约**：处理开始/结束各一条 INFO（含 sessionId、profile 名、停止原因、总耗时）；异常与超时用 WARN/ERROR。

---

## 5. ContextLoader（上下文供给）

```java
public final class ContextLoader {
    /** @param workspace 工作区根目录（即 .oryxos 目录） */
    public ContextLoader(Path workspace);

    /** identity + Bootstrap 文件内容。 */
    public String loadBootstrap(Profile profile);

    /** 读取 skills/<name>/SKILL.md；缺失即抛（既有行为保持）。 */
    public String loadSkill(String skillName);

    /** identity + Bootstrap + Skills，供 PromptBuilder 直接使用。 */
    public String loadSystemContext(Profile profile);
}
```

**解析契约**
- `bootstrap.files` 为**显式引用**：任一缺失 → 抛 `ContextLoadException`。
- 未配置 `bootstrap.files`：按 `AGENTS.md`、`SOUL.md`、`USER.md` 顺序尝试；缺失仅 WARN，处理继续。
- `skills[].name` → `<workspace>/skills/<name>/SKILL.md`，缺失 → 抛 `ContextLoadException`。
- 每次调用都重新读文件（无缓存）。

---

## 6. AgentContext（当前 Agent 配置上下文）

```java
public final class AgentContext {
    public static void set(Profile profile);
    public static Profile current();   // 未设置返回 null
    public static void clear();
}
```

**契约**：线程作用域（`ThreadLocal`）；生命周期严格等于"一次处理"；异常路径也必须清理（FR-010）。

---

## 7. 审计接口

```java
public interface ToolInvocationAuditor {
    void record(String sessionId,
                String profileName,
                String toolName,
                String parameters,
                boolean success,
                String errorMessage,
                String result,
                long latencyMs);
}
```

- 提供 `NoopToolInvocationAuditor`（默认实现，未接入存储时使用），口径与能力一的 `NoopLlmCallAuditor` 一致。
- JPA 实现位于 `oryxos-storage`（`JpaToolInvocationAuditor`）。

---

## 8. 前置校验扩展点

```java
public interface ToolInvocationValidator {
    /** 执行前校验；不通过时抛异常（由 ToolExecutor 捕获为失败结果）。 */
    void validate(OryxTool tool, Map<String, Object> input);
}
```

- 默认实现 `NoopToolInvocationValidator`（全部放行）。
- Sandbox 能力后续提供基于 `SandboxChecker` 的实现；本特性只保证调用点存在且位于"执行之前"。

---

## 9. 异常类型（core）

| 异常 | 触发 | 上层处理 |
|------|------|----------|
| `ProcessingTimeoutException` | 单次处理累计耗时超过上限 | 由入口按 FR-018 展示 |
| `ContextLoadException` | 显式引用的 Bootstrap / SKILL 文件缺失 | 终止本次输入组装并上抛 |
| `AgentConfigurationException` | 请求的 Profile 不存在 | 上抛，返回可读错误 |

---

## 10. 并发契约

- `AgentContext` 使用 `ThreadLocal`；虚拟线程下每请求独占线程，不跨请求泄漏。
- 各组件为无状态或线程安全（`ToolRegistry`、provider 映射表使用并发容器）。
- 核心阶段不做同会话并发合并：同一会话的并发处理由调用方串行化（出范围，见 spec Deferred）。
