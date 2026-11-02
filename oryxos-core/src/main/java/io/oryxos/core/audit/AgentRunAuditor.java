package io.oryxos.core.audit;

import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.react.StopReason;

/**
 * 请求级审计接口：<b>一次处理 = 一条记录</b>（{@code agent_runs} 表）。
 *
 * <p>与 {@link LlmCallAuditor} / {@link ToolInvocationAuditor} 的分工：那两个是"明细"，
 * 本接口是"这一问本身"。后台要回答的问题在这里一次就能拿到：</p>
 * <ul>
 *   <li>问了什么 → {@code userMessage}；</li>
 *   <li>用的哪个模型 → {@code provider} / {@code model}；</li>
 *   <li>花了多少 token、跑了几轮、调了几次工具 → {@code totalUsage} / {@code iterations} / {@code toolCallCount}；</li>
 *   <li>成没成、为什么没成 → {@code stopReason} / {@code success} / {@code errorMessage}。</li>
 * </ul>
 *
 * <p>采用<b>两阶段写</b>（{@link #recordStarted} → {@link #recordFinished}）：处理一开始就先落一条
 * {@code RUNNING}，这样即使进程被 kill / 断电，库里也留着"这次请求来过"的痕迹，
 * 而不是什么都查不到。明细（{@code llm_calls} / {@code tool_invocations}）通过 runId 与其关联。</p>
 *
 * <p>实现必须遵循 FR-020：写入失败只记 ERROR 日志、<b>不抛出</b>，不得阻断主流程。</p>
 */
public interface AgentRunAuditor {

    /**
     * 处理开始：落一条 {@code RUNNING} 记录。
     *
     * <p>此时把"问的是什么、用的什么模型"先写下来——这两项在处理开始时就已确定，
     * 不能让它们依赖处理是否成功。</p>
     *
     * @param runId       本次处理的唯一 ID（同时写入各明细表，用于关联）
     * @param sessionId   会话 ID
     * @param profileName Agent 配置名
     * @param channel     来源渠道（cli / web / schedule）
     * @param userId      用户标识
     * @param userMessage 用户原始输入
     * @param provider    provider 名；配置解析失败时可为 null
     * @param model       模型名；配置解析失败时可为 null
     */
    void recordStarted(String runId,
                       String sessionId,
                       String profileName,
                       String channel,
                       String userId,
                       String userMessage,
                       String provider,
                       String model);

    /**
     * 处理结束：更新同一条记录为 {@code FINISHED}。
     *
     * <p>成功、达轮数上限、空响应、超时、模型报错、配置错误、内部错误都必须调用本方法——
     * 一旦漏调，那条记录会永远停在 {@code RUNNING}，后台看起来就像"卡死了"。</p>
     *
     * @param runId         本次处理的唯一 ID
     * @param stopReason    停止原因（决定 success：仅 {@code FINAL_ANSWER} 为成功）
     * @param iterations    已完成的循环轮数
     * @param toolCallCount 执行的工具调用总数
     * @param totalUsage    累计 token 用量；未知时为 null
     * @param errorMessage  失败原因；成功时为 null
     */
    void recordFinished(String runId,
                        StopReason stopReason,
                        int iterations,
                        int toolCallCount,
                        TokenUsage totalUsage,
                        String errorMessage);
}
