package io.oryxos.tool.config;

import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.builtin.NotifyTool;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配验收：{@code notify} 必须真的进入共享 {@code ToolRegistry}，
 * 且拿到的是同一个沙箱 Bean（不存在"未装配就放行"的形态）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        NotifyAutoConfiguration.class,
        WebhookNotifyAdapter.class,
        NotifyAutoConfigurationTest.TestConfig.class
})
class NotifyAutoConfigurationTest {

    @Configuration
    static class TestConfig {

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry();
        }

        @Bean
        Sandbox sandbox() {
            return new WhitelistSandbox(List.of(), List.of(), List.of("example.com"));
        }
    }

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private NotifyTool notifyTool;

    @Autowired
    private Sandbox sandbox;

    @Test
    void 装配后notify出现在工具注册表中() {
        assertThat(registry.get(NotifyTool.NAME)).isSameAs(notifyTool);
        assertThat(registry.listAll()).extracting(OryxTool::getName).contains(NotifyTool.NAME);
    }

    @Test
    void notify持有装配进来的同一个沙箱() {
        // 沙箱是强制依赖：装配处注入的必须是容器里那个 Bean，而不是各造一个
        assertThat(ReflectionTestUtils.getField(notifyTool, "sandbox")).isSameAs(sandbox);
    }
}
