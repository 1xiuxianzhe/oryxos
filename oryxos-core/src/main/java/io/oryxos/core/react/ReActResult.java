package io.oryxos.core.react;

import io.oryxos.core.llm.LlmClient.TokenUsage;

/**
 * 一次处理的完整结果：不只是答复文本，还带上"过程元数据"。
 *
 * <p>为什么需要它：{@link StopReason#MAX_ITERATIONS} 与 {@link StopReason#EMPTY_RESPONSE}
 * 都是<b>以正常返回值结束</b>的，只返回 {@code String} 的话，调用方无法区分"模型真答复"
 * 与"因上限停止"。请求级审计（{@code agent_runs}）需要这些元数据，因此循环把结局一并返回。</p>
 *
 * @param text          返回给用户的文本（失败收尾时是明确的提示语，不是伪装的正常答复）
 * @param stopReason    停止原因
 * @param iterations    已完成的循环轮数（等于 LLM 调用次数）
 * @param toolCallCount 本次处理执行的工具调用总数
 * @param totalUsage    本次处理累计 token 用量（可能为 provider 未返回用量）
 * @param elapsedMs     本次处理耗时（毫秒）
 */
public record ReActResult(String text,
                          StopReason stopReason,
                          int iterations,
                          int toolCallCount,
                          TokenUsage totalUsage,
                          long elapsedMs) {

    public ReActResult {
        totalUsage = totalUsage == null ? new TokenUsage(0, 0, 0) : totalUsage;
    }

    /** 是否给出了有效答复。只有 {@link StopReason#FINAL_ANSWER} 记为成功。 */
    public boolean success() {
        return stopReason == StopReason.FINAL_ANSWER;
    }
}
