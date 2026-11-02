package io.oryxos.storage.audit;

import io.oryxos.core.audit.AgentRunAuditor;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.react.StopReason;
import io.oryxos.storage.entity.AgentRunEntity;
import io.oryxos.storage.repository.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 基于 JPA 的请求级审计实现：把一次处理写成 {@code agent_runs} 的一条记录。
 *
 * <p>两阶段写：{@code RUNNING} → {@code FINISHED}。这样即使进程被 kill，也能在库里查到
 * "这次请求来过、问了什么、用的哪个模型"，而不是什么都查不到。</p>
 *
 * <p>遵循 FR-020：写入失败只记 ERROR 日志、不抛出——审计不能反过来影响业务。</p>
 */
@Component
public class JpaAgentRunAuditor implements AgentRunAuditor {

    /** 处理进行中。 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 处理已结束（成功或失败）。 */
    public static final String STATUS_FINISHED = "FINISHED";

    private static final Logger logger = LoggerFactory.getLogger(JpaAgentRunAuditor.class);

    private final AgentRunRepository repository;

    public JpaAgentRunAuditor(AgentRunRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void recordStarted(String runId,
                              String sessionId,
                              String profileName,
                              String channel,
                              String userId,
                              String userMessage,
                              String provider,
                              String model) {
        try {
            AgentRunEntity entity = new AgentRunEntity();
            entity.setRunId(runId);
            entity.setSessionId(sessionId);
            entity.setProfileName(profileName);
            entity.setChannel(channel);
            entity.setUserId(userId);
            entity.setUserMessage(userMessage);
            entity.setProvider(provider);
            entity.setModel(model);
            entity.setStatus(STATUS_RUNNING);
            entity.setStartedAt(Instant.now());
            repository.save(entity);
            logger.debug("已写入 agent_runs（RUNNING）: runId={}, sessionId={}, profile={}",
                    runId, sessionId, profileName);
        } catch (RuntimeException e) {
            logger.error("写入 agent_runs（started）失败: runId={}, error={}", runId, e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void recordFinished(String runId,
                               StopReason stopReason,
                               int iterations,
                               int toolCallCount,
                               TokenUsage totalUsage,
                               String errorMessage) {
        try {
            Instant completedAt = Instant.now();
            AgentRunEntity entity = repository.findById(runId).orElseGet(() -> {
                // started 那一步可能失败：这里补一条，宁可字段不全，也不能让这次处理在库里消失
                AgentRunEntity created = new AgentRunEntity();
                created.setRunId(runId);
                created.setStartedAt(completedAt);
                logger.warn("agent_runs 缺少 RUNNING 记录，补写 FINISHED: runId={}", runId);
                return created;
            });

            entity.setStatus(STATUS_FINISHED);
            entity.setStopReason(stopReason == null ? null : stopReason.name());
            entity.setSuccess(stopReason == StopReason.FINAL_ANSWER);
            entity.setIterations(iterations);
            entity.setToolCallCount(toolCallCount);
            if (totalUsage != null) {
                entity.setPromptTokens(totalUsage.promptTokens());
                entity.setCompletionTokens(totalUsage.completionTokens());
                entity.setTotalTokens(totalUsage.totalTokens());
            }
            entity.setErrorMessage(errorMessage);
            entity.setCompletedAt(completedAt);
            repository.save(entity);

            logger.debug("已更新 agent_runs（FINISHED）: runId={}, stopReason={}, success={}, "
                            + "轮数={}, 工具调用={}, tokens={}",
                    runId, entity.getStopReason(), entity.getSuccess(),
                    iterations, toolCallCount, entity.getTotalTokens());
        } catch (RuntimeException e) {
            logger.error("写入 agent_runs（finished）失败: runId={}, error={}", runId, e.getMessage(), e);
        }
    }
}
