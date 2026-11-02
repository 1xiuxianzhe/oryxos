package io.oryxos.tool.builtin;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
 * 目录 Agent 的脚本执行结果语义：非 0 退出即失败、超长输出被截断。
 *
 * <p>依赖本机 {@code python}：这是"跑脚本"这条路径的最小可验证方式；
 * 没有 python 时跳过（而不是让整个构建红掉）。</p>
 */
class ShellToolAgentModeTest {

    @TempDir
    Path workspace;

    private Path agentDir;
    private ShellTool shell;

    @BeforeEach
    void setUp() throws IOException {
        Assumptions.assumeTrue(pythonAvailable(), "本机没有 python，跳过脚本执行相关断言");

        agentDir = workspace.resolve("agents").resolve("demo");
        Files.createDirectories(agentDir.resolve("scripts"));

        Profile profile = new Profile();
        profile.setName("demo");
        profile.setAgentDir(agentDir.toAbsolutePath().normalize());
        AgentContext.set(profile);

        WhitelistSandbox sandbox = new WhitelistSandbox(
                List.of(workspace.toString()), List.of(), List.of("127.0.0.1"), List.of("python"), true);
        shell = new ShellTool(sandbox);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    private static boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("python", "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Path writeScript(String fileName, String content) throws IOException {
        Path script = agentDir.resolve("scripts").resolve(fileName);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        return script;
    }

    @Test
    void 退出码非0视为失败且带上输出尾部() throws IOException {
        Path script = writeScript("boom.py", """
                import sys
                print("before-failure")
                print("boom-detail", file=sys.stderr)
                sys.exit(3)
                """);

        ToolResult result = shell.execute(Map.of("command", "python " + script));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getError()).contains("退出码 3");
        assertThat(result.getError()).contains("boom-detail");
    }

    @Test
    void 成功时正常回填输出() throws IOException {
        Path script = writeScript("fine.py", "print('hello-from-script')");

        ToolResult result = shell.execute(Map.of("command", "python " + script));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("hello-from-script");
    }

    @Test
    void 超长输出被截断并标明() throws IOException {
        Path script = writeScript("flood.py", "print('x' * 40000)");

        ToolResult result = shell.execute(Map.of("command", "python " + script));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent().length()).isLessThan(33000);
        assertThat(result.getContent()).contains("输出已截断");
    }
}
