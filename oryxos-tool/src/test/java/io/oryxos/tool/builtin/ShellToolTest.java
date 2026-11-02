package io.oryxos.tool.builtin;

import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令类内置工具：白名单内可执行、越界被拦且**没有进程被启动**、超时被终止。
 *
 * <p>见 {@code specs/005-tool-system/contracts/tool-invocation.md} 第 6 节验证项 1、2、6、9。</p>
 */
class ShellToolTest {

    private static ShellTool tool(String... allowedCommands) {
        return new ShellTool(new WhitelistSandbox(List.of(), List.of(allowedCommands), List.of()));
    }

    @Test
    void 白名单内命令返回输出() {
        ToolResult result = tool("echo").execute(Map.of("command", "echo oryxos-probe"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("oryxos-probe");
    }

    @Test
    void 白名单外命令被拒_且没有进程被启动(@TempDir Path dir) {
        // "mkdir" 不在白名单内。若真的启动了进程，这个目录会被创建——用它当副作用探针
        Path probe = dir.resolve("probe-dir");

        ToolResult result = tool("echo").execute(Map.of("command", "mkdir " + probe));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("白名单");
        assertThat(Files.exists(probe)).isFalse();
    }

    @Test
    void 越界结果不可重试() {
        ToolResult result = tool("echo").execute(Map.of("command", "rm -rf /"));

        assertThat(result.isSuccess()).isFalse();
        // 越界是配置问题：重试一万次也不会变成允许
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void 命令超时被终止并返回失败() {
        // 注入 300ms 超时，配一条必然更慢的命令，避免测试真的等 30 秒
        ShellTool fast = new ShellTool(
                new WhitelistSandbox(List.of(), List.of("ping"), List.of()),
                Duration.ofMillis(300));

        ToolResult result = fast.execute(Map.of("command", "ping -n 6 127.0.0.1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("超时");
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void 缺少command参数时失败且不可重试() {
        ToolResult result = tool("echo").execute(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("command");
        assertThat(result.isRetryable()).isFalse();
    }
}
