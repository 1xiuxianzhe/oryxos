package io.oryxos.storage.audit;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.audit.ToolInvocationAuditor;
import io.oryxos.storage.entity.ToolInvocationEntity;
import io.oryxos.storage.repository.ToolInvocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 基于 JPA 的工具调用审计实现：把每次工具调用写入 {@code tool_invocations} 表。
 *
 * <p>审计写入失败不得阻断主流程（FR-020）：吞掉异常但绝不静默——至少留下一条 ERROR 日志。</p>
 */
@Component
public class JpaToolInvocationAuditor implements ToolInvocationAuditor {

    private static final Logger logger = LoggerFactory.getLogger(JpaToolInvocationAuditor.class);

    private final ToolInvocationRepository repository;

    public JpaToolInvocationAuditor(ToolInvocationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void record(String sessionId,
                       String profileName,
                       String toolName,
                       String parameters,
                       boolean success,
                       String errorMessage,
                       String result,
                       long latencyMs) {
        try {
            Instant completedAt = Instant.now();
            ToolInvocationEntity entity = new ToolInvocationEntity();
            entity.setInvocationId(UUID.randomUUID().toString());
            entity.setSessionId(sessionId);
            // 从"当前处理"上下文取 runId，把这条明细挂到请求级审计记录上。
            // 不在处理上下文中调用时为 null，明细照常写入，不丢。
            entity.setRunId(AgentContext.currentRunId());
            entity.setProfileName(profileName);
            entity.setToolName(toolName);
            entity.setParameters(parameters);
            entity.setStatus(success ? "SUCCESS" : "FAILED");
            entity.setSuccess(success);
            entity.setErrorMessage(errorMessage);
            entity.setResult(result);
            entity.setLatencyMs(latencyMs);
            entity.setStartedAt(completedAt.minusMillis(Math.max(latencyMs, 0)));
            entity.setCompletedAt(completedAt);
            repository.save(entity);
            logger.debug("已写入 tool_invocations 审计: sessionId={}, tool={}, success={}, latency={}ms",
                    sessionId, toolName, success, latencyMs);
        } catch (RuntimeException e) {
            logger.error("写入 tool_invocations 审计失败: tool={}, error={}", toolName, e.getMessage(), e);
        }
    }
}
