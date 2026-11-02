package io.oryxos.core.config;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentStore;
import io.oryxos.core.agent.WorkspaceWatcher;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.profile.AgentLoader;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.react.PromptBuilder;
import io.oryxos.core.react.ReActLoop;
import io.oryxos.core.react.ToolExecutor;
import io.oryxos.core.schedule.AgentScheduler;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * core 引擎层装配验收测试。
 *
 * <p>验证 {@link CoreAutoConfiguration} 在协作者齐全时能装配出完整 Bean 图；
 * 在必需协作者（{@link LlmClient} / {@link ProfileRegistry}）缺失时给出**可读的错误**，
 * 而不是让应用带着半截上下文启动。</p>
 */
class CoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CoreAutoConfiguration.class)
            .withPropertyValues("oryxos.workspace=target/core-auto-config-test");

    private LlmClient stubLlmClient() {
        return Mockito.mock(LlmClient.class);
    }

    @Test
    void 协作者齐全时_core引擎层Bean齐备() {
        ProfileRegistry registry = new ProfileRegistry();
        runner.withBean(LlmClient.class, this::stubLlmClient)
                .withBean(ProfileRegistry.class, () -> registry)
                .withBean(AgentLoader.class,
                        () -> new AgentLoader(
                                Path.of("target/core-auto-config-test/agents"), Set.of(), registry))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ReActLoop.class);
                    assertThat(context).hasSingleBean(ToolExecutor.class);
                    assertThat(context).hasSingleBean(PromptBuilder.class);
                    assertThat(context).hasSingleBean(ToolRegistry.class);
                    assertThat(context).hasSingleBean(ContextLoader.class);
                    assertThat(context).hasSingleBean(SessionManager.class);
                    assertThat(context).hasSingleBean(AgentService.class);
                    // 第三种触发源：调度线程池 + 调度器一起装配出来
                    assertThat(context).hasSingleBean(TaskScheduler.class);
                    assertThat(context).hasSingleBean(AgentScheduler.class);
                    // 30 节：目录存储 → 生命周期编排 → 目录监听
                    assertThat(context).hasSingleBean(AgentStore.class);
                    assertThat(context).hasSingleBean(AgentLifecycleService.class);
                    assertThat(context).hasSingleBean(WorkspaceWatcher.class);
                    // 未提供审计/校验/记忆/会话存储实现时，可选协作者回退到安全默认
                    assertThat(context.getBean(ReActLoop.class)).isNotNull();
                });
    }

    @Test
    void 缺少LlmClient时_启动失败且错误可读() {
        runner.withBean(ProfileRegistry.class, ProfileRegistry::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("未找到 LlmClient 实现");
                });
    }

    @Test
    void 缺少ProfileRegistry时_启动失败且错误可读() {
        runner.withBean(LlmClient.class, this::stubLlmClient)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("未找到 ProfileRegistry");
                });
    }
}
