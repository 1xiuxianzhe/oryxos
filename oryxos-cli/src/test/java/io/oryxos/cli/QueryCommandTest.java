package io.oryxos.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCommandTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearWorkspaceProperty() {
        System.clearProperty(WorkspaceLayout.WORKSPACE_PROPERTY);
    }

    @Test
    void 初始化后轻命令可以查询空结果() {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        CommandLine command = new CommandLine(new OryxOsCli());

        assertThat(command.execute("init")).isZero();
        assertThat(command.execute("status")).isZero();
        assertThat(command.execute("profile", "list")).isZero();
        assertThat(command.execute("provider", "list")).isZero();
        assertThat(command.execute("session", "list")).isZero();
        // tool list 不再属于轻命令：工具清单必须来自真实注册表，而注册表只在运行时里存在，
        // 因此它改为需要运行时（正/反两个方向由 RuntimeCommandTest 覆盖）。
    }

    @Test
    void 轻命令不应泄漏凭证或完整提示词() throws Exception {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        new CommandLine(new OryxOsCli()).execute("init");
        java.nio.file.Files.writeString(tempDir.resolve(".oryxos/profiles/secret.yml"),
                "apiKey: sk-test\nsystemPrompt: private prompt\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine command = new CommandLine(new OryxOsCli());
        command.setOut(new PrintWriter(out, true));

        assertThat(command.execute("profile", "list")).isZero();
        assertThat(out.toString()).doesNotContain("sk-test", "private prompt");
    }
}
