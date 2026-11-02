package io.oryxos.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录 Agent 的加载与派生：合法目录能注册、非法目录只跳过自己并留下可读原因。
 */
class AgentLoaderTest {

    private static final String LEGAL = """
            ---
            description: 演示 Agent
            provider:
              name: deepseek
              model: deepseek-flash
            tools:
              - get_weather
            schedules:
              - id: hourly
                cron: "0 0 * * * *"
                timezone: Asia/Shanghai
                message: 干活
            ---

            你是演示 Agent，只做一件事。
            """;

    @TempDir
    Path workspace;

    private Path agentsDir() {
        return workspace.resolve("agents");
    }

    private void writeAgent(String name, String content) throws IOException {
        Path dir = agentsDir().resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
    }

    private AgentLoader loader(ProfileRegistry registry) {
        return new AgentLoader(agentsDir(), Set.of("deepseek"), registry);
    }

    @Test
    void 合法目录派生为Profile且带上目录与正文() throws Exception {
        writeAgent("demo-agent", LEGAL);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        int loaded = loader.loadAll();

        assertThat(loaded).isEqualTo(1);
        Profile profile = registry.getProfile("demo-agent");
        assertThat(profile).isNotNull();
        assertThat(profile.isFromAgentDirectory()).isTrue();
        assertThat(profile.getAgentDir()).isEqualTo(agentsDir().resolve("demo-agent").toAbsolutePath().normalize());
        assertThat(profile.getProvider().getModel()).isEqualTo("deepseek-flash");
        assertThat(profile.getSchedules()).hasSize(1);
        assertThat(profile.getTools()).extracting(Profile.ToolConfig::getName).containsExactly("get_weather");
        assertThat(loader.definitions().getFirst().body()).contains("你是演示 Agent");
        assertThat(loader.skippedAgents()).isEmpty();
    }

    @Test
    void 目录名非法被跳过且原因点名() throws Exception {
        writeAgent("Bad_Name", LEGAL);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();

        assertThat(registry.getAllProfiles()).isEmpty();
        assertThat(loader.skippedAgents()).singleElement()
                .satisfies(item -> {
                    assertThat(item.dirName()).isEqualTo("Bad_Name");
                    assertThat(item.reason()).contains("目录名不合法");
                });
    }

    @Test
    void 缺AGENT主文件被跳过() throws Exception {
        Files.createDirectories(agentsDir().resolve("empty-agent").resolve("scripts"));
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();

        assertThat(registry.getAllProfiles()).isEmpty();
        assertThat(loader.skippedAgents()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("AGENT.md"));
    }

    @Test
    void 缺provider段是硬错而不是告警() throws Exception {
        writeAgent("no-provider", """
                ---
                description: 没有 provider
                ---

                正文
                """);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();

        assertThat(registry.getAllProfiles()).isEmpty();
        assertThat(loader.skippedAgents()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("provider"));
    }

    @Test
    void 引用未声明的provider被跳过且消息与legacy一致() throws Exception {
        writeAgent("bad-provider", """
                ---
                provider:
                  name: ghost
                  model: x
                ---

                正文
                """);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();

        assertThat(loader.skippedAgents()).singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("Provider 'ghost' 未在全局层 oryxos.providers 中声明"));
    }

    @Test
    void frontmatter写skills是硬错() throws Exception {
        writeAgent("with-skills", """
                ---
                provider:
                  name: deepseek
                  model: deepseek-flash
                skills:
                  - name: report-format
                    prompt: 格式
                ---

                正文
                """);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();

        assertThat(registry.getAllProfiles()).isEmpty();
        assertThat(loader.skippedAgents()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("不支持 skills"));
    }

    @Test
    void 未知字段被点名并给出timezone提示() throws Exception {
        writeAgent("bad-field", """
                ---
                provider:
                  name: deepseek
                  model: deepseek-flash
                schedules:
                  - id: x
                    cron: "0 0 * * * *"
                    zone: Asia/Shanghai
                ---

                正文
                """);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();

        assertThat(registry.getAllProfiles()).isEmpty();
        assertThat(loader.skippedAgents()).singleElement()
                .satisfies(item -> {
                    assertThat(item.reason()).contains("未知字段");
                    assertThat(item.reason()).contains("timezone");
                });
    }

    @Test
    void frontmatter未闭合被跳过() throws Exception {
        writeAgent("unclosed", """
                ---
                provider:
                  name: deepseek
                """);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();

        assertThat(loader.skippedAgents()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("未闭合"));
    }

    @Test
    void identity对象形态被拍平成文本() throws Exception {
        writeAgent("obj-identity", """
                ---
                identity:
                  agent_name: 小助手
                  prompt: 你只回答天气。
                provider:
                  name: deepseek
                  model: deepseek-flash
                ---

                正文
                """);
        ProfileRegistry registry = new ProfileRegistry();
        loader(registry).loadAll();

        assertThat(registry.getProfile("obj-identity").getIdentity())
                .contains("小助手")
                .contains("你只回答天气。");
    }

    @Test
    void 同名时目录覆盖legacy并记入跳过清单() throws Exception {
        writeAgent("shared", LEGAL);
        ProfileRegistry registry = new ProfileRegistry();
        Profile legacy = new Profile();
        legacy.setName("shared");
        Profile.ProviderConfig provider = new Profile.ProviderConfig();
        provider.setName("deepseek");
        provider.setModel("legacy-model");
        legacy.setProvider(provider);
        registry.register(legacy);

        AgentLoader loader = loader(registry);
        loader.loadAll();

        assertThat(registry.getProfile("shared").getProvider().getModel()).isEqualTo("deepseek-flash");
        assertThat(loader.skippedAgents()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("覆盖"));
    }

    @Test
    void 只有正文没有子资源也是合法Agent() throws Exception {
        writeAgent("minimal", """
                ---
                provider:
                  name: deepseek
                  model: deepseek-flash
                ---

                只说一句话就好。
                """);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();

        assertThat(registry.getProfile("minimal")).isNotNull();
        AgentDefinition definition = loader.definitions().getFirst();
        assertThat(definition.referenceFile()).doesNotExist();
        assertThat(definition.skillsDir()).doesNotExist();
        assertThat(definition.scriptsDir()).doesNotExist();
        assertThat(definition.body()).isEqualTo("只说一句话就好。");
    }

    @Test
    void 每次扫描重置跳过清单() throws Exception {
        writeAgent("Bad_Name", LEGAL);
        ProfileRegistry registry = new ProfileRegistry();
        AgentLoader loader = loader(registry);

        loader.loadAll();
        assertThat(loader.skippedAgents()).hasSize(1);

        Files.delete(agentsDir().resolve("Bad_Name").resolve("AGENT.md"));
        Files.delete(agentsDir().resolve("Bad_Name"));
        writeAgent("ok-agent", LEGAL);

        loader.loadAll();

        List<SkippedAgent> skipped = loader.skippedAgents();
        assertThat(skipped).isEmpty();
        assertThat(registry.getProfile("ok-agent")).isNotNull();
    }
}
