package io.oryxos.core.context;

import io.oryxos.core.profile.Profile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渐进式披露：正文进 prompt、资源不预载、改完即时生效、正文丢失必须失败。
 */
class AgentContextInjectionTest {

    @TempDir
    Path workspace;

    private Path agentDir;

    private Profile agentProfile() throws IOException {
        agentDir = workspace.resolve("agents").resolve("demo");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("AGENT.md"), """
                ---
                provider:
                  name: deepseek
                  model: deepseek-flash
                ---

                你是对账 Agent，只输出结论。
                """, StandardCharsets.UTF_8);

        Profile profile = new Profile();
        profile.setName("demo");
        profile.setIdentity("播报员");
        profile.setAgentDir(agentDir.toAbsolutePath().normalize());
        return profile;
    }

    @Test
    void 正文进systemPrompt而frontmatter不进() throws Exception {
        Profile profile = agentProfile();
        ContextLoader loader = new ContextLoader(workspace);

        String prompt = loader.loadSystemContext(profile);

        assertThat(prompt).contains("你是对账 Agent");
        assertThat(prompt).doesNotContain("deepseek-flash");
        assertThat(prompt).doesNotContain("provider:");
    }

    @Test
    void 资源文件不预载() throws Exception {
        Profile profile = agentProfile();
        Files.writeString(agentDir.resolve("REFERENCE.md"), "参考内容-不应预载");
        Files.createDirectories(agentDir.resolve("skills"));
        Files.writeString(agentDir.resolve("skills").resolve("report-format.md"), "子指令-不应预载");

        String prompt = new ContextLoader(workspace).loadSystemContext(profile);

        assertThat(prompt).doesNotContain("参考内容-不应预载");
        assertThat(prompt).doesNotContain("子指令-不应预载");
    }

    @Test
    void 注入资源目录的绝对路径说明() throws Exception {
        Profile profile = agentProfile();

        String prompt = new ContextLoader(workspace).loadSystemContext(profile);

        assertThat(prompt).contains("你的资源目录");
        assertThat(prompt).contains(agentDir.toAbsolutePath().normalize().toString());
        assertThat(prompt).contains("scripts");
    }

    @Test
    void 改正文后不重启即生效() throws Exception {
        Profile profile = agentProfile();
        ContextLoader loader = new ContextLoader(workspace);
        assertThat(loader.loadSystemContext(profile)).contains("只输出结论");

        Files.writeString(agentDir.resolve("AGENT.md"), """
                ---
                provider:
                  name: deepseek
                  model: deepseek-flash
                ---

                新的正文内容。
                """, StandardCharsets.UTF_8);

        String prompt = loader.loadSystemContext(profile);
        assertThat(prompt).contains("新的正文内容");
        assertThat(prompt).doesNotContain("只输出结论");
    }

    @Test
    void 正文文件被删除时本次处理失败而不是回退旧内容() throws Exception {
        Profile profile = agentProfile();
        ContextLoader loader = new ContextLoader(workspace);
        loader.loadSystemContext(profile);

        Files.delete(agentDir.resolve("AGENT.md"));

        assertThatThrownBy(() -> loader.loadSystemContext(profile))
                .isInstanceOf(ContextLoadException.class)
                .hasMessageContaining("主文件不存在");
    }

    @Test
    void 目录Agent不读工作区默认三件套() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "工作区默认人设-不应出现");
        Profile profile = agentProfile();

        String prompt = new ContextLoader(workspace).loadSystemContext(profile);

        assertThat(prompt).doesNotContain("工作区默认人设-不应出现");
    }

    @Test
    void 显式bootstrap相对agentDir解析() throws Exception {
        Profile profile = agentProfile();
        Files.writeString(agentDir.resolve("EXTRA.md"), "额外设定-应出现");
        Profile.BootstrapConfig bootstrap = new Profile.BootstrapConfig();
        bootstrap.setFiles(List.of("EXTRA.md"));
        profile.setBootstrap(bootstrap);

        String prompt = new ContextLoader(workspace).loadSystemContext(profile);

        assertThat(prompt).contains("额外设定-应出现");
    }

    @Test
    void 显式bootstrap文件缺失则报错() throws Exception {
        Profile profile = agentProfile();
        Profile.BootstrapConfig bootstrap = new Profile.BootstrapConfig();
        bootstrap.setFiles(List.of("NOPE.md"));
        profile.setBootstrap(bootstrap);

        assertThatThrownBy(() -> new ContextLoader(workspace).loadSystemContext(profile))
                .isInstanceOf(ContextLoadException.class)
                .hasMessageContaining("Bootstrap 文件不存在");
    }
}
