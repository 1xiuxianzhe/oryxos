package io.oryxos.core.audit;

/**
 * 空实现的工具调用审计器：不落库，用于未接入存储层的场景（纯单元测试、初始化前）。
 */
public final class NoopToolInvocationAuditor implements ToolInvocationAuditor {

    public static final NoopToolInvocationAuditor INSTANCE = new NoopToolInvocationAuditor();

    private NoopToolInvocationAuditor() {
    }

    @Override
    public void record(String sessionId,
                       String profileName,
                       String toolName,
                       String parameters,
                       boolean success,
                       String errorMessage,
                       String result,
                       long latencyMs) {
        // no-op
    }
}
