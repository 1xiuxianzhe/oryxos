package io.oryxos.provider.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全局层 Provider 配置校验测试。
 */
class ProviderConfigValidatorTest {

    private OryxosProviderProperties.Provider provider(String name, String apiKey) {
        OryxosProviderProperties.Provider provider = new OryxosProviderProperties.Provider();
        provider.setName(name);
        provider.setApiKey(apiKey);
        provider.setBaseUrl("https://api.example.com");
        return provider;
    }

    @Test
    void 合法配置_返回provider名集合() {
        OryxosProviderProperties properties = new OryxosProviderProperties();
        properties.setProviders(List.of(provider("deepseek", "sk-1"), provider("kimi", "sk-2")));

        assertThat(ProviderConfigValidator.validate(properties)).containsExactlyInAnyOrder("deepseek", "kimi");
    }

    @Test
    void 空列表_校验通过() {
        OryxosProviderProperties properties = new OryxosProviderProperties();

        assertThat(ProviderConfigValidator.validate(properties)).isEmpty();
    }

    @Test
    void name为空_抛配置异常() {
        OryxosProviderProperties properties = new OryxosProviderProperties();
        properties.setProviders(List.of(provider("  ", "sk-1")));

        assertThatThrownBy(() -> ProviderConfigValidator.validate(properties))
                .isInstanceOf(ProviderConfigException.class)
                .hasMessageContaining("name");
    }

    @Test
    void name重复_抛配置异常() {
        OryxosProviderProperties properties = new OryxosProviderProperties();
        properties.setProviders(List.of(provider("deepseek", "sk-1"), provider("deepseek", "sk-2")));

        assertThatThrownBy(() -> ProviderConfigValidator.validate(properties))
                .isInstanceOf(ProviderConfigException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void 缺少api_key_抛配置异常() {
        OryxosProviderProperties properties = new OryxosProviderProperties();
        properties.setProviders(List.of(provider("deepseek", "")));

        assertThatThrownBy(() -> ProviderConfigValidator.validate(properties))
                .isInstanceOf(ProviderConfigException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void api_key仍是未解析占位符_抛配置异常() {
        OryxosProviderProperties properties = new OryxosProviderProperties();
        // 环境变量未设置时 Spring 绑定会把占位符原样保留，必须在启动阶段拦住
        properties.setProviders(List.of(provider("deepseek", "${DEEPSEEK_API_KEY}")));

        assertThatThrownBy(() -> ProviderConfigValidator.validate(properties))
                .isInstanceOf(ProviderConfigException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY")
                .hasMessageContaining("未解析");
    }
}
