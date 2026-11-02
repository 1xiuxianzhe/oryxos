package io.oryxos.tool.config;

import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.builtin.NotifyTool;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.NotifyTargetResolver;
import io.oryxos.tool.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Notify 能力装配。
 *
 * <p>{@code ToolRegistry} 只有手动 {@code register()}，没有 Spring 自动收集，
 * 因此工具必须在这里显式登记，否则 Agent 组装 Prompt 时看不到它。</p>
 *
 * <p>本配置**只注册 {@code notify} 一个工具**。{@code FileTool}/{@code ShellTool}/{@code HttpTool}
 * 当前也未接入注册表，那是既有的独立缺口，不在本特性范围内一并处理——
 * 只加 notify 是净增能力，不改变既有行为。</p>
 */
@Configuration
public class NotifyAutoConfiguration {

    @Bean
    public NotifyTargetResolver notifyTargetResolver() {
        return new NotifyTargetResolver();
    }

    /**
     * 沙箱是<b>强制依赖</b>：没有它 {@code notify} 不会装配。
     *
     * <p>005 时期的"未装配则放行 + 一次 WARN"占位状态已取消——那条路等于让运行期存在
     * "没有安全边界"的形态，而"沙箱被显式关闭"（{@code enabled=false}）是另一件事，有醒目告警。</p>
     */
    @Bean
    public NotifyTool notifyTool(NotifyTargetResolver resolver,
                                 NotifyChannelAdapter adapter,
                                 Sandbox sandbox) {
        return new NotifyTool(resolver, adapter, sandbox);
    }

    /**
     * 把 {@code notify} 登记进共享 {@code ToolRegistry}。
     *
     * <p>刻意用一个专门的登记器 Bean（而不是再声明一个 {@code ToolRegistry} 类型的 Bean），
     * 否则会与 {@code CoreAutoConfiguration} 里已有的 {@code ToolRegistry} Bean 冲突，
     * 使按类型注入处出现歧义。</p>
     */
    @Bean
    public NotifyToolRegistration notifyToolRegistration(ToolRegistry registry, NotifyTool notifyTool) {
        return new NotifyToolRegistration(registry, notifyTool);
    }

    /** 仅承担"把工具挂进注册表"这一个副作用。 */
    public static class NotifyToolRegistration implements InitializingBean {

        private static final Logger logger = LoggerFactory.getLogger(NotifyToolRegistration.class);

        private final ToolRegistry registry;
        private final NotifyTool tool;

        NotifyToolRegistration(ToolRegistry registry, NotifyTool tool) {
            this.registry = registry;
            this.tool = tool;
        }

        @Override
        public void afterPropertiesSet() {
            registry.register(tool);
            logger.info("[启动] 已注册内置工具: {}（当前注册表共 {} 个）",
                    tool.getName(), registry.listAll().size());
        }
    }
}
