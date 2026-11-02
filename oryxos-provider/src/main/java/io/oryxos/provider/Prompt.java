package io.oryxos.provider;

import io.oryxos.core.tool.OryxTool;

import java.util.List;
import java.util.Map;

/**
 * 发给 LLM 的一次调用输入。
 *
 * @param systemPrompt   系统提示
 * @param history        对话历史（role/content）
 * @param availableTools 本次可用的工具（Provider 只翻译成 schema，不执行）
 */
public record Prompt(String systemPrompt,
                     List<Map<String, String>> history,
                     List<OryxTool> availableTools) {

    public Prompt {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        history = history == null ? List.of() : List.copyOf(history);
        availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
    }

    /** 便捷构造：只有一条用户消息、无工具。 */
    public static Prompt ofUserMessage(String userMessage) {
        return new Prompt("", List.of(Map.of("role", "user", "content", userMessage)), List.of());
    }
}
