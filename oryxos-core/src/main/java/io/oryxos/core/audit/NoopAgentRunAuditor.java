package io.oryxos.core.audit;

import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.react.StopReason;

/**
 * {@link AgentRunAuditor} 的空实现：不落库。
 *
 * <p>用于 core 单测与"未接入存储"的轻量场景，避免调用方到处判空。</p>
 */
public final class NoopAgentRunAuditor implements AgentRunAuditor {

    public static final NoopAgentRunAuditor INSTANCE = new NoopAgentRunAuditor();

    private NoopAgentRunAuditor() {
    }

    @Override
    public void recordStarted(String runId, String sessionId, String profileName, String channel,
                              String userId, String userMessage, String provider, String model) {
        // 不落库
    }

    @Override
    public void recordFinished(String runId, StopReason stopReason, int iterations,
                               int toolCallCount, TokenUsage totalUsage, String errorMessage) {
        // 不落库
    }
}
