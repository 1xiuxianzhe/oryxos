package io.oryxos.cli;

import io.oryxos.channel.cli.CliChannel;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeCommandTest {

    @Test
    void 重命令没有Spring运行时时应失败而不是伪造成功() {
        CommandLine cli = new CommandLine(new OryxOsCli());

        assertThat(cli.execute("chat")).isNotZero();
        assertThat(cli.execute("serve")).isNotZero();
        assertThat(cli.execute("gateway")).isNotZero();
    }

    @Test
    void serve拒绝非法端口() {
        CommandLine cli = new CommandLine(new OryxOsCli(new RuntimeLauncher() {
            @Override
            public CliChannel chatChannel() {
                return null;
            }
        }));

        assertThat(cli.execute("serve", "--port", "0")).isNotZero();
        assertThat(cli.execute("serve", "--port", "65536")).isNotZero();
    }

    @Test
    void 工具清单没有运行时必须明确失败_不退回目录列举() throws IOException {
        Path workspace = Files.createTempDirectory("oryxos-tool-list-no-runtime");
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, workspace.toString());
        try {
            CommandLine cli = new CommandLine(new OryxOsCli());
            assertThat(cli.execute("init")).isZero();

            assertThat(cli.execute("tool", "list")).isNotZero();
        } finally {
            System.clearProperty(WorkspaceLayout.WORKSPACE_PROPERTY);
        }
    }

    @Test
    void 工具清单来自注册表而不是工作区目录() throws IOException {
        Path workspace = Files.createTempDirectory("oryxos-tool-list-with-runtime");
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, workspace.toString());
        try {
            CommandLine cli = new CommandLine(new OryxOsCli(new RuntimeLauncher() {
                @Override
                public CliChannel chatChannel() {
                    return null;
                }

                @Override
                public List<String> registeredTools() {
                    return List.of("notify", "read_file");
                }
            }));
            assertThat(cli.execute("init")).isZero();

            assertThat(cli.execute("tool", "list")).isZero();
        } finally {
            System.clearProperty(WorkspaceLayout.WORKSPACE_PROPERTY);
        }
    }
}
