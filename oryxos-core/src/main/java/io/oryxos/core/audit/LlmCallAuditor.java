package io.oryxos.core.audit;

import io.oryxos.core.llm.LlmClient.TokenUsage;

/**
 * LLM 调用审计接口。
 *
 * <p>每次 LLM 调用（成功或失败）都必须落一条审计记录到 {@code llm_calls} 表：
 * 只记成功不记失败，等于一次真实事故在系统里没有留下任何痕迹。
 * 由 {@code oryxos-storage} 模块提供基于 JPA 的实现，Provider 只依赖本接口。</p>
 */
public interface LlmCallAuditor {

    /**
     * 记录一次 LLM 调用。
     *
     * @param sessionId    会话 ID
     * @param provider     provider 名（如 deepseek）
     * @param model        模型名
     * @param usage        token 用量；失败时为 null
     * @param success      调用是否成功
     * @param errorMessage 失败原因；成功时为 null
     * @param latencyMs    调用耗时（毫秒）
     */
    void record(String sessionId,
                String provider,
                String model,
                TokenUsage usage,
                boolean success,
                String errorMessage,
                long latencyMs);
}
