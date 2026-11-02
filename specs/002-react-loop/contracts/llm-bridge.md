# Contract: core LlmClient ↔ provider ProviderService 适配

**Feature**: `002-react-loop` | **Date**: 2026-09-16
**决策来源**: [research.md](./research.md) D-01

---

## 1. 为什么需要桥接

- `oryxos-core` 自实现循环，只允许依赖 core 内的抽象（Constitution I / II）。
- `oryxos-provider`（能力一）已交付 `ProviderService.chat(sessionId, Profile, Prompt)`，并负责协议转换与关闭自动执行。
- 依赖方向必须保持 `provider → core`，因此 core 不得直接引用 `ProviderService`。

桥接方案：把 core 的 `LlmClient` 作为循环唯一依赖，由 provider 模块提供实现。

---

## 2. core 侧契约

```java
public interface LlmClient {
    LlmResponse call(String sessionId, Profile profile, LlmRequest request);

    record LlmRequest(String systemPrompt,
                      String memory,
                      List<Map<String, String>> history,
                      List<ToolDescription> tools) {}

    record ToolDescription(String name, String description, String inputSchema) {}

    record LlmResponse(String text, List<ToolCall> toolCalls, TokenUsage tokenUsage) {}

    record ToolCall(String name, String arguments) {}

    record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
}
```

- `sessionId` 与 `Profile` 均定义在 core，签名携带二者不破坏分层。
- `sessionId` 必传：能力一的 `ProviderService` 需要它才能写 `llm_calls` 审计（FR-017）。

---

## 3. provider 侧适配实现

```java
package io.oryxos.provider.client;

public final class ProviderServiceLlmClient implements LlmClient {
    public ProviderServiceLlmClient(ProviderService providerService);
}
```

**映射表**

| core（LlmRequest） | provider（Prompt） | 规则 |
|--------------------|--------------------|------|
| `systemPrompt` | `Prompt.systemPrompt` | 直接透传 |
| `memory`（非空时） | `Prompt.history` 的**第一条** `{"role":"system","content":memory}` | 保证"记忆紧随系统设定、且早于会话历史"的顺序 |
| `history` | `Prompt.history` 的后续条目 | 顺序不变，role/content 原样透传 |
| `tools` | `Prompt.availableTools` | 每个 `ToolDescription` 包装成一个 **schema-only** 的 `OryxTool`（`getInputSchema()` 返回原始 schema，`execute(...)` 直接抛 `UnsupportedOperationException`），再由 provider 既有的 `ToolSchemaAdapter` 转成 Spring AI 工具格式 |

**返回映射**

| provider（LlmResponse） | core（LlmResponse） | 规则 |
|-------------------------|---------------------|------|
| `text` | `text` | 直接透传 |
| `toolCalls(id, name, arguments)` | `ToolCall(name, arguments)` | 丢弃 provider 侧 id（core 侧审计不需要） |
| `usage` | `tokenUsage` | 字段一一对应 |

---

## 4. 契约规则（必须遵守）

1. **不执行工具**：适配器只翻译 schema，不得调用任何工具的 `execute`（Constitution III 的边界在此再次守住）。
2. **不吞异常**：模型调用失败（超时/限流/错误响应）必须原样上抛；能力一已在失败路径落 `llm_calls` 审计。
3. **不做重试与降级**：不切换 provider、不做业务级重试（FR-018）。
4. **不缓存**：适配器无状态，每次调用都走 `ProviderService.chat`。
5. **sessionId 必传**：不得传 null 或空（否则审计关联失效，违反 FR-017）。

---

## 5. 装配

在 `oryxos-provider` 既有的 `ProviderAutoConfiguration` 中新增一个 Bean：

```java
@Bean
public LlmClient llmClient(ProviderService providerService) {
    return new ProviderServiceLlmClient(providerService);
}
```

`oryxos-core` 的 `CoreAutoConfiguration` 以 `ObjectProvider<LlmClient>` 注入（未提供实现时启动失败并给出清晰原因，避免静默拿到 null）。

---

## 6. 验证要点（供 harness 使用）

| 验证点 | 方式 |
|--------|------|
| 顺序正确（记忆在历史之前） | 捕获 `provider.Prompt`，断言 `history[0].role == system` 且内容为记忆 |
| 只翻译不执行 | 断言传给 provider 的工具其 `execute(...)` 会抛 `UnsupportedOperationException` |
| 异常透传 | mock `ProviderService` 抛异常，断言 `LlmClient.call` 原样上抛 |
| sessionId 透传 | 捕获参数，断言与传入一致 |
