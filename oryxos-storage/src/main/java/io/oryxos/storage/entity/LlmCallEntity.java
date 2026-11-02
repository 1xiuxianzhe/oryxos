package io.oryxos.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * llm_calls 表 —— 每次 LLM 调用记录（审计，day one 落库）。
 *
 * <p>成功与失败都要落一条：{@code success} 标记结果，失败时 {@code errorMessage} 记原因，
 * 与 {@code tool_invocations} 表保持对称。</p>
 */
@Entity
@Table(name = "llm_calls")
public class LlmCallEntity {

    @Id
    @Column(name = "call_id")
    private String callId;

    @Column(name = "session_id")
    private String sessionId;

    /** 所属请求级审计记录（agent_runs.run_id）；不在处理上下文中调用时为 null。 */
    @Column(name = "run_id")
    private String runId;

    @Column(name = "provider")
    private String provider;

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "status")
    private String status;

    /** 调用是否成功。 */
    @Column(name = "success")
    private Boolean success;

    /** 失败原因（成功时为 null）。 */
    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @Override
    public String toString() {
        return "LlmCallEntity{" +
                "callId='" + callId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", runId='" + runId + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", promptTokens=" + promptTokens +
                ", completionTokens=" + completionTokens +
                ", totalTokens=" + totalTokens +
                ", latencyMs=" + latencyMs +
                ", status='" + status + '\'' +
                ", success=" + success +
                ", errorMessage='" + errorMessage + '\'' +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
