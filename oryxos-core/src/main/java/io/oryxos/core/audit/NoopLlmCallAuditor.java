package io.oryxos.core.audit;

import io.oryxos.core.llm.LlmClient.TokenUsage;

/**
 * 空实现的审计器：不落库，仅用于未接入存储层的场景（如纯单元测试、CLI 初始化前）。
 */
public final class NoopLlmCallAuditor implements LlmCallAuditor {

    public static final NoopLlmCallAuditor INSTANCE = new NoopLlmCallAuditor();

    private NoopLlmCallAuditor() {
    }

    @Override
    public void record(String sessionId,
                       String provider,
                       String model,
                       TokenUsage usage,
                       boolean success,
                       String errorMessage,
                       long latencyMs) {
        // no-op
    }
}
