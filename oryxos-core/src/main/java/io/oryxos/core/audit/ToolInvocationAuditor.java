package io.oryxos.core.audit;

/**
 * 工具调用审计接口。
 *
 * <p>每次工具调用（无论成功还是失败）都必须落一条记录到 {@code tool_invocations} 表——
 * 与能力一的 {@link LlmCallAuditor} 同一口径：只记成功不记失败，等于一次事故在系统里没有痕迹。</p>
 *
 * <p>由 {@code oryxos-storage} 提供基于 JPA 的实现，core 只依赖本接口。</p>
 */
public interface ToolInvocationAuditor {

    /**
     * 记录一次工具调用。
     *
     * @param sessionId    会话 ID
     * @param profileName  当前 Agent 配置名
     * @param toolName     工具名
     * @param parameters   入参（JSON 字符串）
     * @param success      是否成功
     * @param errorMessage 失败原因；成功时为 null
     * @param result       结果内容；失败时可为 null
     * @param latencyMs    耗时（毫秒）
     */
    void record(String sessionId,
                String profileName,
                String toolName,
                String parameters,
                boolean success,
                String errorMessage,
                String result,
                long latencyMs);
}
