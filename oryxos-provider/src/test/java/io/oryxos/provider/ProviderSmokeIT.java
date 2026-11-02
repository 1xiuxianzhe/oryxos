package io.oryxos.provider;

import io.oryxos.core.audit.LlmCallAuditor;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.profile.Profile;
import io.oryxos.provider.config.OryxosProviderProperties;
import io.oryxos.provider.config.ProviderModelFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成冒烟：用真实 key 真调一次模型，验证「key 对、依赖对、真的通」，并断言审计落了一条 success=true。
 *
 * <p>默认不打 {@code integration} tag 不执行；手动跑：</p>
 * <pre>
 * DEEPSEEK_API_KEY=xxx mvn test -Dgroups=integration
 * </pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class ProviderSmokeIT {

    @Test
    void 真实调用_返回非空响应且审计记成功() {
        OryxosProviderProperties.Provider provider = new OryxosProviderProperties.Provider();
        provider.setName("deepseek");
        provider.setBaseUrl(envOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"));
        provider.setApiKey(System.getenv("DEEPSEEK_API_KEY"));

        OryxosProviderProperties properties = new OryxosProviderProperties();
        properties.setProviders(List.of(provider));

        ProviderModelRegistry registry = ProviderModelFactory.build(properties);
        RecordingAuditor auditor = new RecordingAuditor();
        DefaultProviderService service =
                new DefaultProviderService(registry.asMap(), new ToolSchemaAdapter(), auditor);

        Profile profile = new Profile();
        profile.setName("smoke");
        Profile.ProviderConfig providerConfig = new Profile.ProviderConfig();
        providerConfig.setName("deepseek");
        providerConfig.setModel(envOrDefault("DEEPSEEK_MODEL", "deepseek-chat"));
        profile.setProvider(providerConfig);

        LlmResponse response = service.chat("smoke-session", profile,
                Prompt.ofUserMessage("用一句话打个招呼"));

        assertThat(response.text()).isNotBlank();
        assertThat(auditor.records).hasSize(1);
        assertThat(auditor.records.get(0).success).isTrue();
        assertThat(auditor.records.get(0).provider).isEqualTo("deepseek");
    }

    private String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 记录审计调用的内存实现，供冒烟断言。 */
    static final class RecordingAuditor implements LlmCallAuditor {

        final List<Record> records = new CopyOnWriteArrayList<>();

        @Override
        public void record(String sessionId, String provider, String model, TokenUsage usage,
                           boolean success, String errorMessage, long latencyMs) {
            records.add(new Record(sessionId, provider, model, success, errorMessage));
        }

        record Record(String sessionId, String provider, String model, boolean success, String errorMessage) {
        }
    }
}
