package io.oryxos.provider;

import io.oryxos.core.llm.LlmClient.TokenUsage;

import java.util.List;

/**
 * LLM 返回结果。
 *
 * <p>响应里可能带模型「想调某个工具」的请求，但执行不在这里——原样交回上层
 * 由 {@code ToolExecutor} 执行。</p>
 *
 * @param text     文本回复
 * @param toolCalls 模型请求的工具调用
 * @param usage    token 用量
 */
public record LlmResponse(String text, List<ToolCall> toolCalls, TokenUsage usage) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /** 模型请求的一次工具调用（Provider 只翻译，不执行）。 */
    public record ToolCall(String id, String name, String arguments) {
    }
}
