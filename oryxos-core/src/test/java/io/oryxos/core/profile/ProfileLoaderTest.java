package io.oryxos.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProfileLoader 验收测试。
 * 覆盖：合法 YAML 全字段解析、引用不存在 provider 报错、坏文件不阻断、${ENV} 占位解析。
 */
class ProfileLoaderTest {

    @TempDir
    Path tempDir;

    private void write(String fileName, String content) throws IOException {
        Files.writeString(tempDir.resolve(fileName), content);
    }

    @Test
    void 合法YAML_全字段解析() throws IOException {
        write("ops-agent.yaml", """
                description: 运维助手
                identity: DevOps 专家
                provider:
                  name: deepseek
                  model: deepseek-chat
                  temperature: 0.7
                tools:
                  - name: http_get
                    description: 发起 HTTP GET 请求
                    parameters:
                      - name: url
                        description: 目标地址
                        type: string
                        required: true
                skills:
                  - name: pr-digest
                    description: 生成 PR 摘要
                    prompt: 总结这个 PR
                mcp_servers:
                  - name: weather
                    url: http://localhost:18080
                    api_key: placeholder
                settings:
                  maxHistoryTurns: 20
                """);

        ProfileLoader loader = new ProfileLoader(tempDir, List.of("deepseek"));
        int loaded = loader.loadAll();

        assertThat(loaded).isEqualTo(1);
        Profile profile = loader.get("ops-agent");
        assertThat(profile).isNotNull();
        assertThat(profile.getName()).isEqualTo("ops-agent");
        assertThat(profile.getDescription()).isEqualTo("运维助手");
        assertThat(profile.getIdentity()).isEqualTo("DevOps 专家");

        assertThat(profile.getProvider()).isNotNull();
        assertThat(profile.getProvider().getName()).isEqualTo("deepseek");
        assertThat(profile.getProvider().getModel()).isEqualTo("deepseek-chat");
        assertThat(profile.getProvider().getTemperature()).isEqualTo(0.7);

        assertThat(profile.getTools()).hasSize(1);
        assertThat(profile.getTools().get(0).getName()).isEqualTo("http_get");
        assertThat(profile.getTools().get(0).getParameters()).hasSize(1);
        assertThat(profile.getTools().get(0).getParameters().get(0).getType()).isEqualTo("string");
        assertThat(profile.getTools().get(0).getParameters().get(0).isRequired()).isTrue();

        assertThat(profile.getSkills()).hasSize(1);
        assertThat(profile.getSkills().get(0).getName()).isEqualTo("pr-digest");

        // 下划线字段名 mcp_servers / api_key 需正确映射到驼峰属性
        assertThat(profile.getMcpServers()).hasSize(1);
        assertThat(profile.getMcpServers().get(0).getUrl()).isEqualTo("http://localhost:18080");
        assertThat(profile.getMcpServers().get(0).getApiKey()).isEqualTo("placeholder");

        assertThat(profile.getSettings()).containsEntry("maxHistoryTurns", 20);
    }

    @Test
    void schedules四字段解析_且保留task兼容写法() throws IOException {
        write("daily.yaml", """
                provider:
                  name: deepseek
                  model: deepseek-chat
                schedules:
                  - id: morning-brief
                    cron: "0 0 9 * * *"
                    timezone: Asia/Shanghai
                    message: 汇总昨天的 PR
                  - id: legacy
                    cron: "0 0 18 * * *"
                    task: 旧字段里的任务描述
                """);

        ProfileLoader loader = new ProfileLoader(tempDir, List.of("deepseek"));
        loader.loadAll();

        Profile profile = loader.get("daily");
        assertThat(profile).isNotNull();
        assertThat(profile.getSchedules()).hasSize(2);

        Profile.ScheduleConfig modern = profile.getSchedules().get(0);
        assertThat(modern.getId()).isEqualTo("morning-brief");
        assertThat(modern.getCron()).isEqualTo("0 0 9 * * *");
        assertThat(modern.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(modern.getMessage()).isEqualTo("汇总昨天的 PR");

        // task 是兼容旧字段：没有 message 时由调度层回退使用
        Profile.ScheduleConfig legacy = profile.getSchedules().get(1);
        assertThat(legacy.getId()).isEqualTo("legacy");
        assertThat(legacy.getMessage()).isNull();
        assertThat(legacy.getTask()).isEqualTo("旧字段里的任务描述");
        assertThat(legacy.getTimezone()).isNull();
    }

    @Test
    void 引用不存在的provider_Profile被跳过且不加载() throws IOException {
        write("bad-provider.yaml", """
                provider:
                  name: ghost
                  model: ghost-model
                """);

        ProfileLoader loader = new ProfileLoader(tempDir, List.of("deepseek", "kimi"));
        int loaded = loader.loadAll();

        assertThat(loaded).isZero();
        assertThat(loader.get("bad-provider")).isNull();
    }

    @Test
    void 坏文件_不阻断其余Profile加载() throws IOException {
        write("good.yaml", """
                provider:
                  name: deepseek
                  model: deepseek-chat
                """);
        // 未闭合的流式序列，触发 YAMLException
        write("broken.yaml", "provider: [1, 2\n");

        ProfileLoader loader = new ProfileLoader(tempDir, List.of("deepseek"));
        int loaded = loader.loadAll();

        assertThat(loaded).isEqualTo(1);
        assertThat(loader.get("good")).isNotNull();
        assertThat(loader.get("broken")).isNull();
    }

    @Test
    void 环境变量占位_解析为真实值() throws IOException {
        String key = "ORYXOS_TEST_API_KEY";
        System.setProperty(key, "sk-from-property");
        try {
            write("env-agent.yaml", """
                    provider:
                      name: deepseek
                      model: deepseek-chat
                    mcp_servers:
                      - name: external
                        url: http://example.com
                        api_key: ${ORYXOS_TEST_API_KEY}
                    """);

            ProfileLoader loader = new ProfileLoader(tempDir, List.of("deepseek"));
            loader.loadAll();

            Profile profile = loader.get("env-agent");
            assertThat(profile).isNotNull();
            assertThat(profile.getMcpServers().get(0).getApiKey()).isEqualTo("sk-from-property");
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void substitute_未设置占位符保持原样() {
        assertThat(ProfileLoader.substitute("${ORYXOS_NOT_SET_XYZ}")).isEqualTo("${ORYXOS_NOT_SET_XYZ}");
    }
}
