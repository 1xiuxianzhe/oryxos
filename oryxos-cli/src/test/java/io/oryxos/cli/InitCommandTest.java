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

class InitCommandTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearWorkspaceProperty() {
        System.clearProperty(WorkspaceLayout.WORKSPACE_PROPERTY);
    }

    @Test
    void 初始化创建工作区结构且重复执行不覆盖() throws Exception {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        Path existing = tempDir.resolve(".oryxos/profiles/custom.yaml");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "custom: true\n");

        CommandLine command = new CommandLine(new OryxOsCli());
        assertThat(command.execute("init")).isZero();
        assertThat(command.execute("init")).isZero();

        Path root = tempDir.resolve(".oryxos");
        assertThat(root.resolve("profiles/default.yaml")).exists();
        assertThat(root.resolve("AGENTS.md")).exists();
        assertThat(root.resolve("SOUL.md")).exists();
        assertThat(root.resolve("USER.md")).exists();
        assertThat(root.resolve("memory/MEMORY.md")).exists();
        assertThat(Files.readString(root.resolve("memory/MEMORY.md")))
                .contains("## 核心记忆")
                .contains("## 归档记忆");
        assertThat(root.resolve("MEMORY.md")).doesNotExist();
        assertThat(root.resolve("sessions")).isDirectory();
        assertThat(root.resolve("skills")).isDirectory();
        assertThat(root.resolve("logs")).isDirectory();
        assertThat(root.resolve("tools")).isDirectory();
        assertThat(Files.readString(existing)).isEqualTo("custom: true\n");
    }

    @Test
    void 根目录遗留的记忆被迁移到规范位置并备份旧文件() throws Exception {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        Path root = tempDir.resolve(".oryxos");
        Files.createDirectories(root);
        Files.writeString(root.resolve("MEMORY.md"), "# OryxOS Memory\n用户偏好中文\n");

        assertThat(new CommandLine(new OryxOsCli()).execute("init")).isZero();

        assertThat(Files.readString(root.resolve("memory/MEMORY.md"))).contains("用户偏好中文");
        assertThat(root.resolve("MEMORY.md")).doesNotExist();
        assertThat(root.resolve("MEMORY.md.bak")).exists();
    }

    @Test
    void 未初始化工作区给出错误结果() {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY,
                tempDir.resolve("missing").toString());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CommandLine command = new CommandLine(new OryxOsCli());
        command.setErr(new PrintWriter(err, true));

        assertThat(command.execute("status")).isNotZero();
        assertThat(err.toString()).contains("未初始化");
    }

    /** 回归：生成的默认 Profile 必须能被真正的 ProfileLoader 解析，否则 chat 启动即报 Profile 不存在。 */
    @Test
    void 生成的默认Profile能被ProfileLoader解析() {
        System.setProperty(WorkspaceLayout.WORKSPACE_PROPERTY, tempDir.toString());
        assertThat(new CommandLine(new OryxOsCli()).execute("init")).isZero();

        ProfileLoader loader = new ProfileLoader(
                tempDir.resolve(".oryxos/profiles"), List.of("deepseek"));

        assertThat(loader.loadAll()).isEqualTo(1);
        Profile profile = loader.get("default");
        assertThat(profile).isNotNull();
        assertThat(profile.getProvider()).isNotNull();
        assertThat(profile.getProvider().getName()).isEqualTo("deepseek");
        assertThat(profile.getProvider().getModel()).isNotBlank();
        assertThat(profile.getChannels()).hasSize(1);
        assertThat(profile.getChannels().get(0).getType()).isEqualTo("cli");
    }
}
