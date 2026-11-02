package io.oryxos.web.dto;

import java.util.List;

/**
 * 会话运行明细（审计视图）的传输模型。
 *
 * <p>数据来自 SQLite 的三张审计表，不是内存会话：</p>
 * <ul>
 *   <li>{@code agent_runs} —— 一次提问一条（问了什么、用的哪个模型、几轮、成没成）；</li>
 *   <li>{@code llm_calls} —— 每次模型调用一条（token 与耗时在这里）；</li>
 *   <li>{@code tool_invocations} —— 每次工具调用一条（参数、结果、成败）。</li>
 * </ul>
 *
 * <p>三层结构按 {@code run_id} 聚合：会话 → 每次提问 → 该次提问下的模型/工具调用。
 * 这样前端既能回答"这段对话问了什么"，也能回答"钱花在哪一轮"。</p>
 */
public final class SessionAuditDtos {

    private SessionAuditDtos() {
    }

    /**
     * 会话级汇总。
     *
     * @param runs        提问次数（{@code agent_runs} 行数）
     * @param llmCalls    模型调用次数（{@code llm_calls} 行数，含失败与重试的中间尝试）
     * @param toolCalls   工具调用次数（{@code tool_invocations} 行数）
     * @param totalTokens prompt + completion（失败调用没有 token，计 0）
     * @param durationMs  各次提问耗时之和；RUNNING 中的记录不计
     */
    public record Totals(int runs,
                         int llmCalls,
                         int toolCalls,
                         long promptTokens,
                         long completionTokens,
                         long totalTokens,
                         long durationMs) {
    }

    /** 会话运行明细：汇总 + 每次提问展开。 */
    public record SessionAudit(String sessionId, Totals totals, List<RunView> runs) {
    }

    /**
     * 一次提问的完整账。
     *
     * @param userMessage 用户原始输入（{@code agent_runs.user_message}）
     * @param status      RUNNING / FINISHED
     * @param stopReason  结局：FINAL_ANSWER / MAX_ITERATIONS / TIMEOUT / MODEL_ERROR / INTERNAL_ERROR 等
     * @param success     仅 FINAL_ANSWER 为 true
     * @param iterations  循环轮数（**不含重试**；有重试时小于 {@code llmCalls.size()}）
     * @param durationMs  completedAt - startedAt；RUNNING 时为 null
     */
    public record RunView(String runId,
                          String userMessage,
                          String profileName,
                          String channel,
                          String provider,
                          String model,
                          String status,
                          String stopReason,
                          Boolean success,
                          Integer iterations,
                          Integer toolCallCount,
                          Integer promptTokens,
                          Integer completionTokens,
                          Integer totalTokens,
                          Long durationMs,
                          String errorMessage,
                          String startedAt,
                          String completedAt,
                          List<LlmCallView> llmCalls,
                          List<ToolCallView> toolCalls) {
    }

    /** 一次模型调用。 */
    public record LlmCallView(String callId,
                              String provider,
                              String model,
                              Integer promptTokens,
                              Integer completionTokens,
                              Integer totalTokens,
                              Long latencyMs,
                              Boolean success,
                              String errorMessage,
                              String startedAt) {
    }

    /**
     * 一次工具调用。
     *
     * @param parameters      调用参数（已截断）
     * @param result          执行结果（已截断）
     * @param resultTruncated 结果是否被截断——显式标记，避免把"结果就这么短"和"被切了"混为一谈
     */
    public record ToolCallView(String invocationId,
                               String toolName,
                               String parameters,
                               String result,
                               boolean resultTruncated,
                               Long latencyMs,
                               Boolean success,
                               String errorMessage,
                               String startedAt) {
    }
}
