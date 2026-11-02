package io.oryxos.cli;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileCommandTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperty() {
        System.clearProperty(WorkspaceLayout.WORKSPACE_PROPERTY);
    }

    @Test
    void 可以创建查看和删除Profile但保护默认Profile() throws Exception {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        CommandLine cli = new CommandLine(new OryxOsCli());
        assertThat(cli.execute("init")).isZero();
        assertThat(cli.execute("profile", "create", "demo")).isZero();
        assertThat(Files.exists(tempDir.resolve(".oryxos/profiles/demo.yaml"))).isTrue();
        assertThat(cli.execute("profile", "create", "demo")).isNotZero();
        assertThat(cli.execute("profile", "delete", "default")).isNotZero();
        assertThat(cli.execute("profile", "delete", "demo")).isZero();
        assertThat(Files.exists(tempDir.resolve(".oryxos/profiles/demo.yaml"))).isFalse();
    }

    /** 回归：新建的 Profile 必须能被 ProfileLoader 解析。 */
    @Test
    void 新建的Profile可以被ProfileLoader解析() {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        CommandLine cli = new CommandLine(new OryxOsCli());
        cli.execute("init");
        assertThat(cli.execute("profile", "create", "demo")).isZero();

        ProfileLoader loader = new ProfileLoader(
                tempDir.resolve(".oryxos/profiles"), List.of("deepseek"));

        assertThat(loader.loadAll()).isEqualTo(2);
        Profile demo = loader.get("demo");
        assertThat(demo).isNotNull();
        assertThat(demo.getProvider().getName()).isEqualTo("deepseek");
    }

    @Test
    void Profile详情不会输出敏感字段() throws Exception {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        CommandLine cli = new CommandLine(new OryxOsCli());
        cli.execute("init");
        Files.writeString(tempDir.resolve(".oryxos/profiles/demo.yaml"),
                "name: demo\napiKey: sk-secret\npassword: hidden\nsystemPrompt: private\nprovider: default\n");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(output, true));
        assertThat(cli.execute("profile", "show", "demo")).isZero();
        assertThat(output.toString()).doesNotContain("sk-secret", "hidden", "private");
    }

    /** US4 验收：通知渠道的 webhook 地址通常内嵌凭证，详情展示不得泄漏。 */
    @Test
    void 通知渠道地址不会出现在Profile详情中() throws Exception {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        CommandLine cli = new CommandLine(new OryxOsCli());
        cli.execute("init");
        Files.writeString(tempDir.resolve(".oryxos/profiles/hooked.yaml"),
                "name: hooked\n"
                        + "provider:\n  name: deepseek\n  model: deepseek-chat\n"
                        + "notify_channels:\n"
                        + "  - type: webhook\n"
                        + "    config:\n"
                        + "      url: https://qyapi.example.com/hook?key=SECRET123\n");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(output, true));
        assertThat(cli.execute("profile", "show", "hooked")).isZero();

        assertThat(output.toString())
                .doesNotContain("SECRET123")
                .doesNotContain("https://qyapi.example.com/hook?key=SECRET123");
    }

    /** 默认模板里给出的 notify_channels 注释示例同样不得泄漏占位地址。 */
    @Test
    void 默认Profile模板的详情展示不泄漏示例地址() {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        CommandLine cli = new CommandLine(new OryxOsCli());
        cli.execute("init");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cli.setOut(new PrintWriter(output, true));
        assertThat(cli.execute("profile", "show", "default")).isZero();

        assertThat(output.toString()).doesNotContain("TEAM_WEBHOOK_URL");
    }
}
