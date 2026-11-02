package io.oryxos.core.llm;

import java.util.List;
import java.util.Map;

/**
 * 一次模型调用的输入值对象。
 *
 * <p>四部分的含义与顺序由 {@code PromptBuilder} 决定，装配形态见
 * `specs/002-react-loop/contracts/agent-runtime.md` 第 2 节与 `research.md` D-03：</p>
 * <ol>
 *   <li>{@code systemPrompt}：角色设定 + Bootstrap + SKILL + 当前日期时间（第一段）</li>
 *   <li>{@code memory}：长期记忆片段（为空则不产生消息，且位于会话历史之前）</li>
 *   <li>{@code history}：最近 N 次完整往返的会话历史（已截断）</li>
 *   <li>{@code tools}：可用工具 schema（不占消息位，随请求单独传递）</li>
 * </ol>
 */
public record LlmRequest(String systemPrompt,
                         String memory,
                         List<Map<String, String>> history,
                         List<LlmClient.ToolDescription> tools) {

    public LlmRequest {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        memory = memory == null ? "" : memory;
        history = history == null ? List.of() : List.copyOf(history);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public boolean hasMemory() {
        return !memory.isBlank();
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
