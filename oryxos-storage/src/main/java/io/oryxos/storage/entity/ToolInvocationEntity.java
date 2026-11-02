package io.oryxos.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * tool_invocations 表 —— 每次 Tool 调用记录（审计，day one 落库）。
 *
 * <p>口径与 {@code llm_calls} 对齐：保留 {@code status}，另有 {@code success} /
 * {@code error_message} / {@code latency_ms}；成功与失败都要落一条。</p>
 */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocationEntity {

    @Id
    @Column(name = "invocation_id")
    private String invocationId;

    @Column(name = "session_id")
    private String sessionId;

    /** 所属请求级审计记录（agent_runs.run_id）；不在处理上下文中调用时为 null。 */
    @Column(name = "run_id")
    private String runId;

    @Column(name = "profile_name")
    private String profileName;

    @Column(name = "tool_name")
    private String toolName;

    @Column(name = "parameters", length = 10000)
    private String parameters;

    @Column(name = "status")
    private String status;

    /** 调用是否成功。 */
    @Column(name = "success")
    private Boolean success;

    /** 失败原因（成功时为 null）。 */
    @Column(name = "error_message", length = 10000)
    private String errorMessage;

    @Column(name = "result", length = 100000)
    private String result;

    /** 调用耗时（毫秒）。 */
    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public String getInvocationId() {
        return invocationId;
    }

    public void setInvocationId(String invocationId) {
        this.invocationId = invocationId;
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

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
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

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
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
        return "ToolInvocationEntity{" +
                "invocationId='" + invocationId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", runId='" + runId + '\'' +
                ", profileName='" + profileName + '\'' +
                ", toolName='" + toolName + '\'' +
                ", status='" + status + '\'' +
                ", success=" + success +
                ", errorMessage='" + errorMessage + '\'' +
                ", latencyMs=" + latencyMs +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
