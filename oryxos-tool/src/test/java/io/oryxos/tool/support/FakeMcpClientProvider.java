package io.oryxos.tool.support;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.tool.mcp.McpClientProvider;
import io.oryxos.tool.mcp.McpServerDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可控的外部服务连接替身。
 *
 * <p>用它是为了在不启动真实进程、不连网络的前提下，验证"某个服务失联时只告警、不阻断"这条
 * 最关键的行为——这要求测试能精确指定谁连得上、谁连不上。</p>
 *
 * <p>只有"如何建立连接"这一层被替换；适配、注册、隔离逻辑仍走真实实现。</p>
 */
public class FakeMcpClientProvider implements McpClientProvider {

    private final Map<String, McpSyncClient> serving = new LinkedHashMap<>();

    private final Set<String> failing = new LinkedHashSet<>();

    private final List<McpSyncClient> connections = new ArrayList<>();

    /** 让该名字的服务连接成功并返回指定客户端。 */
    public FakeMcpClientProvider serve(String serverName, McpSyncClient client) {
        serving.put(serverName, client);
        return this;
    }

    /** 让该名字的服务连接失败。 */
    public FakeMcpClientProvider fail(String serverName) {
        failing.add(serverName);
        return this;
    }

    /** 实际被建立过的连接，用于断言回收行为。 */
    public List<McpSyncClient> connections() {
        return List.copyOf(connections);
    }

    @Override
    public McpSyncClient connect(McpServerDeclaration declaration) {
        String name = declaration.name();
        if (failing.contains(name)) {
            throw new IllegalStateException("连接被拒绝（测试替身）: " + name);
        }
        McpSyncClient client = serving.get(name);
        if (client == null) {
            throw new IllegalStateException("替身未定义该服务: " + name);
        }
        connections.add(client);
        return client;
    }

    // ---------- 构造假客户端的便捷方法 ----------

    /** 造一个"报出一个工具"的客户端。 */
    public static McpSyncClient clientWithTool(String toolName, String description) {
        return clientWithTools(List.of(tool(toolName, description)));
    }

    public static McpSyncClient clientWithTools(List<McpSchema.Tool> tools) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(tools, null));
        return client;
    }

    /** 造一个"列出工具时就失败"的客户端（模拟握手成功但随即失联）。 */
    public static McpSyncClient clientFailingOnListTools() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenThrow(new IllegalStateException("外部服务已失联"));
        return client;
    }

    public static McpSchema.Tool tool(String name, String description) {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("city", Map.of("type", "string")),
                List.of("city"),
                null);
        return new McpSchema.Tool(name, description, schema);
    }
}
