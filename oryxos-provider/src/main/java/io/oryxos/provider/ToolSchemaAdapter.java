package io.oryxos.provider;

import io.oryxos.core.tool.OryxTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * 工具适配器 —— 把 OryxOS 内部的 {@link OryxTool} 翻译成 Spring AI 的工具格式。
 *
 * <p><b>只翻译，不执行。</b>适配产物只包含工具说明（name/description/inputSchema），
 * 其 {@code call} 直接抛异常——因为 Spring AI 的自动执行已被禁用，真正的执行由
 * {@code ReActLoop} + {@code ToolExecutor} 控制，绕开这里就不会被执行。</p>
 */
public class ToolSchemaAdapter {

    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    /**
     * 批量把 OryxTool 翻译成 Spring AI 的 {@link ToolCallback}（仅为 schema 载体）。
     */
    public List<ToolCallback> toSpringAiTools(List<OryxTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream().map(this::toToolCallback).toList();
    }

    /** 把单个 OryxTool 翻译成 Spring AI 工具格式。 */
    public ToolCallback toToolCallback(OryxTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool 不能为 null");
        }
        String schema = tool.getInputSchema();
        if (schema == null || schema.isBlank()) {
            schema = EMPTY_SCHEMA;
        }
        ToolDefinition definition = ToolDefinition.builder()
                .name(tool.getName())
                .description(tool.getDescription() == null ? "" : tool.getDescription())
                .inputSchema(schema)
                .build();
        return new SchemaOnlyToolCallback(definition);
    }

    /**
     * 仅承载 schema 的 ToolCallback：不含任何执行逻辑。
     */
    static final class SchemaOnlyToolCallback implements ToolCallback {

        private final ToolDefinition definition;

        SchemaOnlyToolCallback(ToolDefinition definition) {
            this.definition = definition;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException(
                    "Provider 只翻译工具 schema，不执行工具；工具执行由 ToolExecutor 负责");
        }
    }
}
