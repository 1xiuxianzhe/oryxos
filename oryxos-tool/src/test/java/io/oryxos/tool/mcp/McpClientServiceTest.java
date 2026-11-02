package io.oryxos.tool.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.support.FakeMcpClientProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部服务接入的隔离契约：一条失败不影响启动、不影响其它服务、不影响内置工具。
 *
 * <p>见 {@code specs/005-tool-system/contracts/mcp-client.md} 验证项 2、3、4、7、9。</p>
 */
class McpClientServiceTest {

    @TempDir
    Path workspace;

    private void declare(String yaml) throws IOException {
        Files.writeString(workspace.resolve(McpServerConfigLoader.FILE_NAME), yaml);
    }

    private static String stdioYaml(String... names) {
        StringBuilder sb = new StringBuilder("servers:\n");
        for (String name : names) {
            sb.append("  - name: ").append(name).append('\n')
                    .append("    transport: stdio\n")
                    .append("    command: npx\n");
        }
        return sb.toString();
    }

    private McpClientService service(ToolRegistry registry, FakeMcpClientProvider provider) {
        return new McpClientService(registry, provider, new McpServerConfigLoader(workspace));
    }

    private static OryxTool builtin(String name) {
        return new OryxTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "内置-" + name;
            }

            @Override
            public String getInputSchema() {
                return "{}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                return ToolResult.ok("builtin");
            }
        };
    }

    @Test
    void 未声明服务时不注册任何工具() {
        ToolRegistry registry = new ToolRegistry();
        McpClientService service = service(registry, new FakeMcpClientProvider());

        service.connectAll();

        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void 正常服务的工具被注册且形态与内置一致() throws IOException {
        declare(stdioYaml("weather"));
        ToolRegistry registry = new ToolRegistry();
        FakeMcpClientProvider provider = new FakeMcpClientProvider()
                .serve("weather", FakeMcpClientProvider.clientWithTool("weather_forecast", "查天气"));

        service(registry, provider).connectAll();

        assertThat(registry.contains("weather_forecast")).isTrue();
        OryxTool tool = registry.get("weather_forecast");
        assertThat(tool.getName()).isEqualTo("weather_forecast");
        assertThat(tool.getDescription()).isEqualTo("查天气");
        assertThat(tool.getInputSchema()).isNotBlank();
    }

    @Test
    void 单服务失联时启动不失败且其工具不出现() throws IOException {
        declare(stdioYaml("dead"));
        ToolRegistry registry = new ToolRegistry();
        FakeMcpClientProvider provider = new FakeMcpClientProvider().fail("dead");

        assertThatCode(() -> service(registry, provider).connectAll()).doesNotThrowAnyException();
        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void 一好一坏时好的可用且内置工具全可用() throws IOException {
        declare(stdioYaml("good", "bad"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(builtin("read_file"));
        FakeMcpClientProvider provider = new FakeMcpClientProvider()
                .serve("good", FakeMcpClientProvider.clientWithTool("good_tool", "好服务"))
                .fail("bad");

        service(registry, provider).connectAll();

        assertThat(registry.contains("good_tool")).isTrue();
        assertThat(registry.contains("read_file")).isTrue();
        // 坏服务没有任何工具进来
        assertThat(registry.listAll()).hasSize(2);
    }

    @Test
    void 列出工具时失联也被隔离() throws IOException {
        declare(stdioYaml("flaky"));
        ToolRegistry registry = new ToolRegistry();
        FakeMcpClientProvider provider = new FakeMcpClientProvider()
                .serve("flaky", FakeMcpClientProvider.clientFailingOnListTools());

        assertThatCode(() -> service(registry, provider).connectAll()).doesNotThrowAnyException();
        assertThat(registry.listAll()).isEmpty();
    }

    @Test
    void 与内置工具撞名时内置胜出() throws IOException {
        declare(stdioYaml("weather"));
        ToolRegistry registry = new ToolRegistry();
        OryxTool builtinTool = builtin("weather_forecast");
        registry.register(builtinTool);
        FakeMcpClientProvider provider = new FakeMcpClientProvider()
                .serve("weather", FakeMcpClientProvider.clientWithTool("weather_forecast", "外部查天气"));

        service(registry, provider).connectAll();

        // 内置能力是地基，不应被外部依赖顶掉
        assertThat(registry.get("weather_forecast")).isSameAs(builtinTool);
    }

    @Test
    void 停机时关闭已建立的连接() throws IOException {
        declare(stdioYaml("weather"));
        ToolRegistry registry = new ToolRegistry();
        McpSyncClient client = FakeMcpClientProvider.clientWithTool("weather_forecast", "查天气");
        FakeMcpClientProvider provider = new FakeMcpClientProvider().serve("weather", client);

        McpClientService service = service(registry, provider);
        service.connectAll();
        service.close();

        verify(client).close();
    }

    @Test
    void 连接失败的服务不会被尝试关闭() throws IOException {
        declare(stdioYaml("dead"));
        ToolRegistry registry = new ToolRegistry();
        FakeMcpClientProvider provider = new FakeMcpClientProvider().fail("dead");

        McpClientService service = service(registry, provider);
        service.connectAll();
        service.close();

        assertThat(provider.connections()).isEmpty();
    }

    @Test
    void 装配后自动连接_无需显式调用() throws IOException {
        declare(stdioYaml("weather"));
        ToolRegistry registry = new ToolRegistry();
        FakeMcpClientProvider provider = new FakeMcpClientProvider()
                .serve("weather", FakeMcpClientProvider.clientWithTool("weather_forecast", "查天气"));

        McpClientService service = service(registry, provider);
        service.afterPropertiesSet();

        assertThat(registry.contains("weather_forecast")).isTrue();
    }

    @Test
    void 一个服务报出多个工具时全部注册() throws IOException {
        declare(stdioYaml("weather"));
        ToolRegistry registry = new ToolRegistry();
        McpSyncClient client = FakeMcpClientProvider.clientWithTools(List.of(
                FakeMcpClientProvider.tool("forecast", "查预报"),
                FakeMcpClientProvider.tool("air_quality", "查空气质量")));
        FakeMcpClientProvider provider = new FakeMcpClientProvider().serve("weather", client);
        // 让替身客户端在注册时即可用（listTools 已在 clientWithTools 中桩好）
        when(client.listTools()).thenReturn(new io.modelcontextprotocol.spec.McpSchema.ListToolsResult(
                List.of(FakeMcpClientProvider.tool("forecast", "查预报"),
                        FakeMcpClientProvider.tool("air_quality", "查空气质量")), null));

        service(registry, provider).connectAll();

        assertThat(registry.listAll()).hasSize(2);
    }
}
