package io.oryxos.provider.config;

import io.oryxos.core.profile.ProfileLoader;
import io.oryxos.provider.ProviderModelRegistry;
import io.oryxos.provider.ProviderService;
import io.oryxos.provider.ToolSchemaAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider 模块 Spring 装配测试：验证显式映射表与 ProviderService 能按配置正确装配。
 */
class ProviderAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProviderAutoConfiguration.class)
            .withPropertyValues("oryxos.workspace=target/test-workspace");

    @Test
    void 未配置provider_仍能装配且映射表为空() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ProviderService.class);
            assertThat(context).hasSingleBean(ToolSchemaAdapter.class);
            assertThat(context).hasSingleBean(ProfileLoader.class);
            ProviderModelRegistry registry = context.getBean(ProviderModelRegistry.class);
            assertThat(registry.size()).isZero();
        });
    }

    @Test
    void 配置两个provider_显式映射表建立() {
        runner.withPropertyValues(
                        "oryxos.providers[0].name=deepseek",
                        "oryxos.providers[0].base-url=https://api.deepseek.com",
                        "oryxos.providers[0].api-key=sk-deepseek",
                        "oryxos.providers[1].name=kimi",
                        "oryxos.providers[1].base-url=https://api.moonshot.cn/v1",
                        "oryxos.providers[1].api-key=sk-kimi")
                .run(context -> {
                    ProviderModelRegistry registry = context.getBean(ProviderModelRegistry.class);
                    assertThat(registry.size()).isEqualTo(2);
                    assertThat(registry.contains("deepseek")).isTrue();
                    assertThat(registry.contains("kimi")).isTrue();
                });
    }

    @Test
    void 配置provider缺少api_key_启动失败并给出清晰错误() {
        runner.withPropertyValues(
                        "oryxos.providers[0].name=deepseek",
                        "oryxos.providers[0].base-url=https://api.deepseek.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(ProviderConfigException.class);
                });
    }
}
