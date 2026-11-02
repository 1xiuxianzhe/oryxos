package io.oryxos.tool.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外部服务声明文件的解析契约。
 *
 * <p>见 {@code specs/005-tool-system/contracts/mcp-client.md} 验证项 1、8。</p>
 */
class McpServerConfigLoaderTest {

    @TempDir
    Path workspace;

    private void writeConfig(String content) throws IOException {
        Files.writeString(workspace.resolve(McpServerConfigLoader.FILE_NAME), content);
    }

    @Test
    void 文件缺失时返回空列表() {
        assertThat(new McpServerConfigLoader(workspace).load()).isEmpty();
    }

    @Test
    void 服务列表为空时返回空列表() throws IOException {
        writeConfig("servers: []\n");

        assertThat(new McpServerConfigLoader(workspace).load()).isEmpty();
    }

    @Test
    void 本地子进程声明被完整解析() throws IOException {
        writeConfig("""
                servers:
                  - name: weather
                    transport: stdio
                    command: npx
                    args: ["-y", "@acme/weather-mcp"]
                    env:
                      ACME_API_KEY: hardcoded
                """);

        List<McpServerDeclaration> declarations = new McpServerConfigLoader(workspace).load();

        assertThat(declarations).hasSize(1);
        McpServerDeclaration declaration = declarations.get(0);
        assertThat(declaration.name()).isEqualTo("weather");
        assertThat(declaration.isStdio()).isTrue();
        assertThat(declaration.command()).isEqualTo("npx");
        assertThat(declaration.argsOrEmpty()).containsExactly("-y", "@acme/weather-mcp");
        assertThat(declaration.envOrEmpty()).containsEntry("ACME_API_KEY", "hardcoded");
    }

    @Test
    void 远程服务声明被完整解析() throws IOException {
        writeConfig("""
                servers:
                  - name: ops
                    transport: sse
                    url: http://localhost:18080/sse
                """);

        List<McpServerDeclaration> declarations = new McpServerConfigLoader(workspace).load();

        assertThat(declarations).hasSize(1);
        assertThat(declarations.get(0).isSse()).isTrue();
        assertThat(declarations.get(0).url()).isEqualTo("http://localhost:18080/sse");
    }

    @Test
    void 环境变量占位被解析() throws IOException {
        String key = "ORYXOS_TEST_MCP_URL";
        System.setProperty(key, "http://resolved.example.com/sse");
        try {
            writeConfig("""
                    servers:
                      - name: ops
                        transport: sse
                        url: ${ORYXOS_TEST_MCP_URL}
                    """);

            List<McpServerDeclaration> declarations = new McpServerConfigLoader(workspace).load();

            assertThat(declarations.get(0).url()).isEqualTo("http://resolved.example.com/sse");
        } finally {
            System.clearProperty(key);
        }
    }

    // ---------- 非法条目：跳过但不阻断 ----------

    @Test
    void 缺少name的条目被跳过() throws IOException {
        writeConfig("""
                servers:
                  - transport: stdio
                    command: npx
                """);

        assertThat(new McpServerConfigLoader(workspace).load()).isEmpty();
    }

    @Test
    void transport取值非法的条目被跳过() throws IOException {
        writeConfig("""
                servers:
                  - name: weird
                    transport: carrier-pigeon
                    command: npx
                """);

        assertThat(new McpServerConfigLoader(workspace).load()).isEmpty();
    }

    @Test
    void 缺少transport的条目被跳过() throws IOException {
        writeConfig("""
                servers:
                  - name: weather
                    command: npx
                """);

        assertThat(new McpServerConfigLoader(workspace).load()).isEmpty();
    }

    @Test
    void 本地子进程缺少command的条目被跳过() throws IOException {
        writeConfig("""
                servers:
                  - name: weather
                    transport: stdio
                    args: ["-y"]
                """);

        assertThat(new McpServerConfigLoader(workspace).load()).isEmpty();
    }

    @Test
    void 远程服务缺少url的条目被跳过() throws IOException {
        writeConfig("""
                servers:
                  - name: ops
                    transport: sse
                """);

        assertThat(new McpServerConfigLoader(workspace).load()).isEmpty();
    }

    @Test
    void 非法条目与合法条目共存时只保留合法的() throws IOException {
        writeConfig("""
                servers:
                  - name: broken
                    transport: sse
                  - name: weather
                    transport: stdio
                    command: npx
                """);

        List<McpServerDeclaration> declarations = new McpServerConfigLoader(workspace).load();

        assertThat(declarations).extracting(McpServerDeclaration::name).containsExactly("weather");
    }
}
