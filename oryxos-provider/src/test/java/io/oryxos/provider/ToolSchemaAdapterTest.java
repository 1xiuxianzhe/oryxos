package io.oryxos.provider;

import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具适配器验收测试：OryxTool 的 schema 翻译成 Spring AI 格式后字段一一对齐，且只翻译、不执行。
 */
class ToolSchemaAdapterTest {

    private final ToolSchemaAdapter adapter = new ToolSchemaAdapter();

    private OryxTool tool(String name, String description, String schema) {
        return new OryxTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public String getInputSchema() {
                return schema;
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                return ToolResult.ok("executed");
            }
        };
    }

    @Test
    void schema字段一一对齐() {
        String schema = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";
        List<ToolCallback> callbacks = adapter.toSpringAiTools(
                List.of(tool("weather", "查询天气", schema)));

        assertThat(callbacks).hasSize(1);
        ToolDefinition definition = callbacks.get(0).getToolDefinition();
        assertThat(definition.name()).isEqualTo("weather");
        assertThat(definition.description()).isEqualTo("查询天气");
        assertThat(definition.inputSchema()).isEqualTo(schema);
    }

    @Test
    void 只翻译不执行_调用即抛异常() {
        ToolCallback callback = adapter.toToolCallback(tool("http_get", "HTTP GET", "{}"));

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("不执行");
    }

    @Test
    void schema为空时使用默认空对象schema() {
        ToolCallback callback = adapter.toToolCallback(tool("noop", "无参工具", null));

        assertThat(callback.getToolDefinition().inputSchema())
                .isEqualTo("{\"type\":\"object\",\"properties\":{}}");
    }

    @Test
    void 空列表翻译为空() {
        assertThat(adapter.toSpringAiTools(null)).isEmpty();
        assertThat(adapter.toSpringAiTools(List.of())).isEmpty();
    }
}
