package io.oryxos.tool.builtin;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录 Agent 的文件访问：相对路径按 agentDir 解析，越界被拒。
 */
class FileToolAgentModeTest {

    @TempDir
    Path workspace;

    private Path agentDir;
    private FileTool readFile;

    @BeforeEach
    void setUp() throws IOException {
        agentDir = workspace.resolve("agents").resolve("demo");
        Files.createDirectories(agentDir.resolve("skills"));
        Files.writeString(agentDir.resolve("skills").resolve("report-format.md"), "格式内容",
                StandardCharsets.UTF_8);

        Profile profile = new Profile();
        profile.setName("demo");
        profile.setAgentDir(agentDir.toAbsolutePath().normalize());
        AgentContext.set(profile);

        WhitelistSandbox sandbox = new WhitelistSandbox(
                List.of(workspace.toString()), List.of(), List.of("127.0.0.1"), List.of("python"), true);
        readFile = new FileTool("read_file", sandbox);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void 相对路径按agentDir解析() {
        var result = readFile.execute(Map.of("path", "skills/report-format.md"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("格式内容");
    }

    @Test
    void 越界路径被拒绝() {
        var result = readFile.execute(Map.of("path", "../other/secret.txt"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("不在该 Agent 目录内");
    }
}
