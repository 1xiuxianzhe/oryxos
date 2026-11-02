package io.oryxos.core.llm;

import io.oryxos.core.profile.Profile;

import java.util.List;

/**
 * core 层对 LLM 的抽象接口。
 *
 * <p>ReAct 循环只依赖本抽象，不依赖任何具体 Provider 模块的实现（依赖方向保持
 * provider → core）。由 {@code oryxos-provider} 提供适配实现。</p>
 *
 * <p>签名携带 {@code sessionId} 与 {@link Profile}：前者是审计关联所必需（FR-017），
 * 后者决定本次调用走哪个 provider / model / 温度。</p>
 */
public interface LlmClient {

    /**
     * 调用 LLM。
     *
     * @param sessionId 会话 ID（必传，用于审计关联）
     * @param profile   当前 Agent 配置
     * @param request   输入（系统设定 / 长期记忆 / 会话历史 / 工具 schema）
     * @return LLM 响应
     */
    LlmResponse call(String sessionId, Profile profile, LlmRequest request);

    /**
     * 工具描述，供 LLM function calling 使用。
     */
    record ToolDescription(String name, String description, String inputSchema) {
    }

    /**
     * LLM 响应封装。
     *
     * <p>{@code toolCalls} 归一化为非 null 列表，调用方只需判空集合。</p>
     */
    record LlmResponse(String text, List<ToolCall> toolCalls, TokenUsage tokenUsage) {

        public LlmResponse {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        /** 是否请求了工具调用——循环的停止判定只看这一项（FR-003）。 */
        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }

        public boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    /**
     * LLM 要求的工具调用。
     */
    record ToolCall(String name, String arguments) {
    }

    /**
     * Token 用量。
     */
    record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}
