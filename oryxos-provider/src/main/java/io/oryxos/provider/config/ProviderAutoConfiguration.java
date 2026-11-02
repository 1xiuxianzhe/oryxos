package io.oryxos.provider.config;

import io.oryxos.core.audit.LlmCallAuditor;
import io.oryxos.core.audit.NoopLlmCallAuditor;
import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.profile.AgentLoader;
import io.oryxos.core.profile.ProfileLoader;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.SkippedAgent;
import io.oryxos.provider.DefaultProviderService;
import io.oryxos.provider.client.ProviderServiceLlmClient;
import io.oryxos.provider.ProviderModelRegistry;
import io.oryxos.provider.ProviderService;
import io.oryxos.provider.ToolSchemaAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.nio.file.Path;
import java.util.Set;

/**
 * Provider 模块的 Spring 装配。
 *
 * <p>启动顺序（日志中按 {@code [启动 N/4]} 分步可见）：</p>
 * <ol>
 *   <li>读取并校验全局层配置 {@code oryxos.providers}；</li>
 *   <li>按 provider name 建立 {@code name → ChatModel} 显式映射表；</li>
 *   <li>扫描并加载 Profiles（顺带校验 Profile 引用的 provider 是否存在）；</li>
 *   <li>装配 {@link ProviderService}（审计器若存在则注入，否则用空实现）。</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(OryxosProviderProperties.class)
public class ProviderAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ProviderAutoConfiguration.class);

    private static final String DEFAULT_WORKSPACE = ".oryxos";

    @Bean
    public ProviderModelRegistry providerModelRegistry(OryxosProviderProperties properties) {
        logger.info("[启动 1/4] 读取全局层配置 oryxos.providers ...");
        Set<String> providerNames = ProviderConfigValidator.validate(properties);
        logger.info("[启动 1/4] 配置校验通过，共 {} 个 provider: {}", providerNames.size(), providerNames);

        logger.info("[启动 2/4] 建立 provider name → ChatModel 显式映射表 ...");
        ProviderModelRegistry registry = ProviderModelFactory.build(properties);
        logger.info("[启动 2/4] 映射表建立完成，已注册 provider: {}", registry.asMap().keySet());
        return registry;
    }

    /**
     * 启动时扫描工作区 {@code .oryxos/profiles} 下的所有 Profile，坏的只记日志、不阻断启动。
     */
    @Bean
    @DependsOn("providerModelRegistry")
    public ProfileLoader profileLoader(OryxosProviderProperties properties) {
        Set<String> providerNames = ProviderConfigValidator.validate(properties);
        Path profilesDir = resolveProfilesDir(properties);

        logger.info("[启动 3/4] 加载 Profile，目录: {}（Profile 引用的 provider 必须已在上一步注册）", profilesDir);
        ProfileLoader loader = new ProfileLoader(profilesDir, providerNames);
        int loaded = loader.loadAll();
        logger.info("[启动 3/4] Profile 加载完成，成功 {} 个: {}", loaded, loader.all().keySet());
        return loader;
    }

    @Bean
    public ToolSchemaAdapter oryxToolSchemaAdapter() {
        return new ToolSchemaAdapter();
    }

    @Bean
    @DependsOn("profileLoader")
    public ProviderService providerService(ProviderModelRegistry registry,
                                           ToolSchemaAdapter toolSchemaAdapter,
                                           ObjectProvider<LlmCallAuditor> auditorProvider) {
        LlmCallAuditor auditor = auditorProvider.getIfAvailable(() -> NoopLlmCallAuditor.INSTANCE);
        logger.info("[启动 4/4] 装配 ProviderService，审计实现: {}", simpleClassName(auditor));
        return new DefaultProviderService(registry.asMap(), toolSchemaAdapter, auditor);
    }

    /** 把 core 的 LlmClient 抽象接到 ProviderService 上（依赖方向保持 provider → core）。 */
    @Bean
    public LlmClient llmClient(ProviderService providerService) {
        logger.info("[启动] 已装配 LlmClient 适配器: ProviderServiceLlmClient");
        return new ProviderServiceLlmClient(providerService);
    }

    /**
     * 扫描工作区 {@code .oryxos/agents/} 下的目录 Agent，派生 Profile 并注册进**同一个**注册表。
     *
     * <p>放在这里而不是 core：列表校验需要全局层已声明的 provider 名，那属于 Provider 模块的知识。
     * 注册表用的是 {@link ProfileLoader#registry()}，因此目录 Agent 与 legacy Profile
     * 天然共享同一个索引——同名覆盖才成立。</p>
     *
     * <p>坏目录只跳过自己，原因进 {@link AgentLoader#skippedAgents()}，不阻断启动。</p>
     */
    @Bean
    @DependsOn("profileLoader")
    public AgentLoader agentLoader(ProfileLoader profileLoader, OryxosProviderProperties properties) {
        Set<String> providerNames = ProviderConfigValidator.validate(properties);
        Path agentsDir = resolveWorkspace(properties).resolve("agents");

        logger.info("[启动 3/4] 扫描目录 Agent: {}", agentsDir);
        AgentLoader loader = new AgentLoader(agentsDir, providerNames, profileLoader.registry());
        int loaded = loader.loadAll();
        logger.info("[启动 3/4] 目录 Agent 加载完成：成功 {} 个，跳过 {} 个",
                loaded, loader.skippedAgents().size());
        for (SkippedAgent item : loader.skippedAgents()) {
            logger.warn("[启动 3/4] 跳过的目录 Agent: dir={}, reason={}", item.dirName(), item.reason());
        }
        return loader;
    }

    /**
     * 把启动时加载好的 Profile 索引暴露给 AgentService 使用。
     *
     * <p>{@code @DependsOn("agentLoader")} 不是可选的：{@code AgentScheduler} 在
     * {@code afterPropertiesSet} 里就遍历注册表注册定时任务，若目录 Agent 还没扫进来，
     * 它们的 {@code schedules} 会**静默丢失**（表现为"配了定时却从不触发"）。</p>
     */
    @Bean
    @DependsOn("agentLoader")
    public ProfileRegistry profileRegistry(ProfileLoader profileLoader) {
        return profileLoader.registry();
    }

    /** 取干净的类型名，去掉 Spring CGLIB 代理后缀（如 JpaLlmCallAuditor$$SpringCGLIB$$0）。 */
    private static String simpleClassName(Object bean) {
        String name = bean.getClass().getSimpleName();
        int proxySuffix = name.indexOf("$$");
        return proxySuffix > 0 ? name.substring(0, proxySuffix) : name;
    }

    /**
     * 容器就绪后打印一份装配摘要，方便一眼确认这一阶段的启动结果。
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> providerReadyLogger(ProviderModelRegistry registry,
                                                                          ProfileLoader profileLoader,
                                                                          AgentLoader agentLoader) {
        return event -> {
            logger.info("========== OryxOS Provider 就绪 ==========");
            logger.info("  providers     : {}", registry.asMap().keySet());
            logger.info("  profiles      : {}", profileLoader.all().keySet());
            logger.info("  profiles 目录 : {}", profileLoader.profilesDir());
            logger.info("  agents 目录   : {}（成功 {} 个，跳过 {} 个）",
                    agentLoader.agentsDir(),
                    agentLoader.definitions().size(),
                    agentLoader.skippedAgents().size());
            logger.info("==========================================");
        };
    }

    private Path resolveProfilesDir(OryxosProviderProperties properties) {
        return resolveWorkspace(properties).resolve("profiles");
    }

    private Path resolveWorkspace(OryxosProviderProperties properties) {
        String workspace = properties.getWorkspace();
        if (workspace == null || workspace.isBlank()) {
            workspace = Path.of(System.getProperty("user.dir"), DEFAULT_WORKSPACE).toString();
        }
        return Path.of(workspace);
    }
}
