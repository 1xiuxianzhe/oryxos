package io.oryxos.tool.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.support.FakeMcpClientProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部工具到统一抽象的适配契约。
 *
 * <p>见 {@code specs/005-tool-system/contracts/mcp-client.md} 验证项 2、5、6。</p>
 */
class McpToolAdapterTest {

    private static McpSyncClient clientReturning(McpSchema.CallToolResult result) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenReturn(result);
        return client;
    }

    private static McpToolAdapter adapter(McpSyncClient client) {
        return new McpToolAdapter(client, FakeMcpClientProvider.tool("weather_forecast", "查天气"));
    }

    @Test
    void 名字与描述来自外部工具() {
        McpToolAdapter adapter = adapter(mock(McpSyncClient.class));

        assertThat(adapter.getName()).isEqualTo("weather_forecast");
        assertThat(adapter.getDescription()).isEqualTo("查天气");
    }

    @Test
    void 描述缺失时退回工具名而不是留空() {
        McpSchema.Tool tool = new McpSchema.Tool("bare_tool", "   ",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null));

        McpToolAdapter adapter = new McpToolAdapter(mock(McpSyncClient.class), tool);

        // 契约三件套要求描述非空，否则模型不知道何时该调它
        assertThat(adapter.getDescription()).isEqualTo("bare_tool");
    }

    @Test
    void 结构化schema被序列化为json文本并保留参数名() {
        McpToolAdapter adapter = adapter(mock(McpSyncClient.class));

        // 仓库的统一形态是 JSON 文本，而外部服务给的是结构化对象，这里必须转换到位
        assertThat(adapter.getInputSchema())
                .contains("\"city\"")
                .contains("\"required\"")
                .contains("object");
    }

    @Test
    void 调用时参数原样转发() {
        McpSyncClient client = clientReturning(new McpSchema.CallToolResult(
                List.<McpSchema.Content>of(new McpSchema.TextContent("晴 25 度")), false));

        adapter(client).execute(Map.of("city", "北京"));

        ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("weather_forecast");
        assertThat(captor.getValue().arguments()).containsEntry("city", "北京");
    }

    @Test
    void 成功时内容块拼接为文本() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.<McpSchema.Content>of(
                        new McpSchema.TextContent("第一行"),
                        new McpSchema.TextContent("第二行")),
                false);

        ToolResult toolResult = adapter(clientReturning(result)).execute(Map.of());

        assertThat(toolResult.isSuccess()).isTrue();
        assertThat(toolResult.getContent()).isEqualTo("第一行\n第二行");
    }

    @Test
    void 外部服务报告执行失败时是失败且可重试() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.<McpSchema.Content>of(new McpSchema.TextContent("城市不存在")), true);

        ToolResult toolResult = adapter(clientReturning(result)).execute(Map.of());

        assertThat(toolResult.isSuccess()).isFalse();
        assertThat(toolResult.getError()).contains("城市不存在");
        assertThat(toolResult.isRetryable()).isTrue();
    }

    @Test
    void 调用抛异常时是失败且可重试_不把异常抛给上层的Agent() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenThrow(new IllegalStateException("连接已断开"));

        ToolResult toolResult = adapter(client).execute(Map.of());

        assertThat(toolResult.isSuccess()).isFalse();
        assertThat(toolResult.getError()).contains("连接已断开");
        assertThat(toolResult.isRetryable()).isTrue();
    }
}
