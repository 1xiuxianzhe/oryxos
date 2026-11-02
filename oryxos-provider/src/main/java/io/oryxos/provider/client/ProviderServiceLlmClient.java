package io.oryxos.provider.client;

import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.llm.LlmRequest;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.provider.ProviderService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * core 的 {@link LlmClient} 到能力一 {@link ProviderService} 的适配器。
 *
 * <p>存在的意义：core 自实现循环，但不得依赖 provider 模块（依赖方向必须保持 provider → core）。
 * 因此由 provider 侧提供实现，把 core 的调用形状翻译成 Provider 的调用形状。</p>
 *
 * <p>三条硬规则（见 `specs/002-react-loop/contracts/llm-bridge.md`）：只翻译不执行、
 * 不吞异常、不做重试与降级。</p>
 */
public final class ProviderServiceLlmClient implements LlmClient {

    private final ProviderService providerService;

    public ProviderServiceLlmClient(ProviderService providerService) {
        this.providerService = providerService;
    }

    @Override
    public LlmResponse call(String sessionId, Profile profile, LlmRequest request) {
        // 长期记忆作为独立 system 消息，紧随主 system 消息、早于会话历史（顺序契约）
        List<Map<String, String>> history = new ArrayList<>();
        if (request.hasMemory()) {
            history.add(Map.of("role", "system", "content", request.memory()));
        }
        history.addAll(request.history());

        List<OryxTool> tools = new ArrayList<>(request.tools().size());
        for (ToolDescription description : request.tools()) {
            tools.add(new SchemaOnlyTool(description.name(), description.description(), description.inputSchema()));
        }

        io.oryxos.provider.LlmResponse response =
                providerService.chat(sessionId, profile,
                        new io.oryxos.provider.Prompt(request.systemPrompt(), history, tools));

        List<ToolCall> toolCalls = response.toolCalls().stream()
                .map(call -> new ToolCall(call.name(), call.arguments()))
                .toList();

        TokenUsage usage = response.usage() == null ? null : new TokenUsage(
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.usage().totalTokens());

        return new LlmResponse(response.text(), toolCalls, usage);
    }

    /**
     * 仅承载 schema 的 OryxTool：Provider 只翻译工具说明，执行权仍在 ToolExecutor。
     */
    private record SchemaOnlyTool(String toolName, String toolDescription, String toolSchema) implements OryxTool {

        @Override
        public String getName() {
            return toolName;
        }

        @Override
        public String getDescription() {
            return toolDescription == null ? "" : toolDescription;
        }

        @Override
        public String getInputSchema() {
            return toolSchema == null || toolSchema.isBlank()
                    ? "{\"type\":\"object\",\"properties\":{}}"
                    : toolSchema;
        }

        @Override
        public ToolResult execute(Map<String, Object> input) {
            throw new UnsupportedOperationException(
                    "Provider 只翻译工具 schema，不执行工具；工具执行由 ToolExecutor 负责");
        }
    }
}
