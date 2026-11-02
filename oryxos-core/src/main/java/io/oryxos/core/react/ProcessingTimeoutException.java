package io.oryxos.core.react;

import io.oryxos.core.llm.LlmClient.TokenUsage;

/**
 * 单次处理的累计耗时超过上限时抛出。
 *
 * <p>由 {@code AgentService} 按 FR-018 上抛给调用入口展示；不得被转成"看似正常"的答复。</p>
 *
 * <p>超时属于"失败但已有部分进展"：异常同时携带<b>已完成的轮数、已执行的工具调用数、已消耗的 token</b>，
 * 使请求级审计即使在中止场景下也能记下真实进度，而不是只剩一句错误信息。</p>
 */
public class ProcessingTimeoutException extends RuntimeException {

    private final long timeoutSeconds;

    private final int completedIterations;

    private final int toolCallCount;

    private final TokenUsage usage;

    public ProcessingTimeoutException(long timeoutSeconds) {
        this(timeoutSeconds, 0, 0, null);
    }

    public ProcessingTimeoutException(long timeoutSeconds,
                                      int completedIterations,
                                      int toolCallCount,
                                      TokenUsage usage) {
        super("单次处理超过整体耗时上限（" + timeoutSeconds + " 秒），已中止");
        this.timeoutSeconds = timeoutSeconds;
        this.completedIterations = completedIterations;
        this.toolCallCount = toolCallCount;
        this.usage = usage == null ? new TokenUsage(0, 0, 0) : usage;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** 超时发生时已完成的循环轮数。 */
    public int getCompletedIterations() {
        return completedIterations;
    }

    /** 超时发生时已执行的工具调用总数。 */
    public int getToolCallCount() {
        return toolCallCount;
    }

    /** 超时发生前已消耗的 token 用量。 */
    public TokenUsage getUsage() {
        return usage;
    }
}
