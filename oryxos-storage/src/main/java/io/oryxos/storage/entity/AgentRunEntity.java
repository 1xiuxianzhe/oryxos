package io.oryxos.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * agent_runs 表 —— 请求级审计：一次处理（一次用户提问）一条记录。
 *
 * <p>存在的意义是让后台能直接回答："这次请求问了什么、用的哪个模型、花了多少 token、
 * 跑了几轮、调了几次工具、成没成、没成是为什么"——不需要去别的表里拼。</p>
 *
 * <p>两阶段写：处理开始落一条 {@code RUNNING}（此时 {@code userMessage} / {@code provider} /
 * {@code model} 就已写入），处理结束更新为 {@code FINISHED} 并补齐结果字段。
 * 若进程中途被 kill，那条 {@code RUNNING} 记录本身就是"这次请求来过"的证据。</p>
 *
 * <p>明细关联：{@code llm_calls.run_id} 与 {@code tool_invocations.run_id} 指向本表的 {@code run_id}。</p>
 */
@Entity
@Table(name = "agent_runs")
public class AgentRunEntity {

    @Id
    @Column(name = "run_id")
    private String runId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "profile_name")
    private String profileName;

    @Column(name = "channel")
    private String channel;

    @Column(name = "user_id")
    private String userId;

    /** 用户原始输入——后台要展示"问的是什么"。 */
    @Column(name = "user_message", length = 10000)
    private String userMessage;

    @Column(name = "provider")
    private String provider;

    @Column(name = "model")
    private String model;

    /** RUNNING / FINISHED。 */
    @Column(name = "status")
    private String status;

    /** 停止原因，取 {@code StopReason} 的枚举名。 */
    @Column(name = "stop_reason")
    private String stopReason;

    /** 是否成功：仅 FINAL_ANSWER 记为成功。 */
    @Column(name = "success")
    private Boolean success;

    /** 已完成的循环轮数（等于 LLM 调用次数）。 */
    @Column(name = "iterations")
    private Integer iterations;

    /** 本次处理执行的工具调用总数。 */
    @Column(name = "tool_call_count")
    private Integer toolCallCount;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** 失败原因（成功时为 null）。 */
    @Column(name = "error_message", length = 10000)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Integer getIterations() {
        return iterations;
    }

    public void setIterations(Integer iterations) {
        this.iterations = iterations;
    }

    public Integer getToolCallCount() {
        return toolCallCount;
    }

    public void setToolCallCount(Integer toolCallCount) {
        this.toolCallCount = toolCallCount;
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
        return "AgentRunEntity{" +
                "runId='" + runId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", profileName='" + profileName + '\'' +
                ", channel='" + channel + '\'' +
                ", userId='" + userId + '\'' +
                ", provider='" + provider + '\'' +
                ", model='" + model + '\'' +
                ", status='" + status + '\'' +
                ", stopReason='" + stopReason + '\'' +
                ", success=" + success +
                ", iterations=" + iterations +
                ", toolCallCount=" + toolCallCount +
                ", promptTokens=" + promptTokens +
                ", completionTokens=" + completionTokens +
                ", totalTokens=" + totalTokens +
                ", errorMessage='" + errorMessage + '\'' +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
