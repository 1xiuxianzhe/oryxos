# Contract: MemoryService（统一门面，core）

**契约对象**：`io.oryxos.core.memory.MemoryService`（接口）+ `io.oryxos.core.memory.MemoryScope`（枚举）
**实现**：`io.oryxos.memory.DefaultMemoryService`（oryxos-memory）
**消费方**：`io.oryxos.core.react.PromptBuilder`（读取）、记忆工具（写入/检索）

> 这是本特性最重要的一道契约：它是"接口墙"。墙之上（引擎）只认这三个方法；墙之下是文件、还是将来的向量库，引擎不感知（FR-011）。

## 接口

```java
package io.oryxos.core.memory;

public interface MemoryService {

    /** 取回用于组装 Prompt 的长期记忆：核心区完整 + 归档区（必要时截断）。
     *  无记忆时返回空串，绝不返回 null，也绝不抛出。 */
    String buildContext(Session session);

    /** 记住一条。失败时抛出异常，由调用方（工具层）转成失败的 ToolResult。 */
    void remember(String content, MemoryScope scope);

    /** 在归档区按关键词检索。未命中返回空列表，不抛出。 */
    List<String> recall(String keyword);
}
```

```java
public enum MemoryScope {
    CORE,       // 核心记忆：始终在场，不检索、不截断
    ARCHIVAL    // 归档记忆：可检索、可截断
}
```

## 行为约定

| # | 约定 | 依据 |
|---|---|---|
| C-1 | `buildContext` 只返回**长期记忆**，**不含**会话历史 | C-002 / FR-009 |
| C-2 | `buildContext` 对"文件缺失/区块缺失/IO 失败"一律返回空串（或非空部分），**不抛异常** | FR-010 / I-06 |
| C-3 | `buildContext` 每次调用都重新读文件，**不得缓存** | FR-005 |
| C-4 | `buildContext` 的核心区部分**逐字节完整**，只有归档区可能被截断 | FR-006 / FR-007 |
| C-5 | `remember(content=null/空白)` 必须被拒绝（抛 `IllegalArgumentException`），不得写入空条目 | 数据质量 |
| C-6 | `remember` 落入的区块由 `scope` 决定；`scope` 为 `null` 时按 `ARCHIVAL` 处理 | FR-002 |
| C-7 | `recall(keyword=null/空白)` 返回空列表（**不得**返回全量归档区） | FR-008 |
| C-8 | `recall` 大小写不敏感，**只搜归档区**（核心记忆永远在场，无需检索） | FR-008 / US3 |
| C-9 | 同一实例内 `remember` 之后立刻 `buildContext`/`recall` 必须可见（无缓存、无延迟） | FR-005 / US1 |

## 接线契约（core 侧改动）

| 位置 | 改动 | 要求 |
|---|---|---|
| `PromptBuilder` | 构造参数由旧读端口改为 `MemoryService`；`recallMemory(Session)` 内部改为 `memoryService.buildContext(session)` | 门面为 `null` 时返回空串（保持"未装配也不炸"的既有语义） |
| `PromptBuilder` | 删除 `lastUserMessage(...)` 私有方法 | 第二部分不再做关键词召回（D-02） |
| `CoreAutoConfiguration.promptBuilder` | `ObjectProvider<MemoryReader>` → `ObjectProvider<MemoryService>` | 仍是**可选**依赖：缺失时记忆段为空，应用照常启动 |
| `io.oryxos.core.memory.MemoryReader` | 删除 | 被门面取代（C-001） |
| `ReActLoop` | 不改 | 记忆不参与循环控制流 |

## 非契约（明确不做）

- 不承诺"按 `session` 隔离记忆"——参数为扩展阶段预留，核心阶段按工作区共享（A-001）。
- 不承诺跨进程可见性时序（单进程单工作区）。
- 不提供删除/清空记忆的 API（记忆只追加，FR-012）。
