package io.oryxos.storage.audit;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.audit.LlmCallAuditor;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.storage.entity.LlmCallEntity;
import io.oryxos.storage.repository.LlmCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 基于 JPA 的 LLM 调用审计实现：把每次调用写入 {@code llm_calls} 表。
 *
 * <p>审计写入失败不应影响主流程（调用结果对用户更重要），因此这里吞掉异常并记错误日志——
 * 但绝不静默：至少留下一条 ERROR 日志。</p>
 */
@Component
public class JpaLlmCallAuditor implements LlmCallAuditor {

    private static final Logger logger = LoggerFactory.getLogger(JpaLlmCallAuditor.class);

    private final LlmCallRepository repository;

    public JpaLlmCallAuditor(LlmCallRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void record(String sessionId,
                       String provider,
                       String model,
                       TokenUsage usage,
                       boolean success,
                       String errorMessage,
                       long latencyMs) {
        try {
            Instant completedAt = Instant.now();
            LlmCallEntity entity = new LlmCallEntity();
            entity.setCallId(UUID.randomUUID().toString());
            entity.setSessionId(sessionId);
            // 从"当前处理"上下文取 runId，把这条明细挂到请求级审计记录上。
            // 不在处理上下文中调用（如直接调 Provider）时为 null，明细照常写入，不丢。
            entity.setRunId(AgentContext.currentRunId());
            entity.setProvider(provider);
            entity.setModel(model);
            if (usage != null) {
                entity.setPromptTokens(usage.promptTokens());
                entity.setCompletionTokens(usage.completionTokens());
                entity.setTotalTokens(usage.totalTokens());
            }
            entity.setLatencyMs(latencyMs);
            entity.setSuccess(success);
            entity.setStatus(success ? "SUCCESS" : "FAILED");
            entity.setErrorMessage(errorMessage);
            entity.setStartedAt(completedAt.minusMillis(Math.max(latencyMs, 0)));
            entity.setCompletedAt(completedAt);
            repository.save(entity);
            logger.debug("已写入 llm_calls 审计: sessionId={}, provider={}, model={}, success={}, latency={}ms",
                    sessionId, provider, model, success, latencyMs);
        } catch (RuntimeException e) {
            logger.error("写入 llm_calls 审计失败: provider={}, model={}, error={}", provider, model, e.getMessage(), e);
        }
    }
}
