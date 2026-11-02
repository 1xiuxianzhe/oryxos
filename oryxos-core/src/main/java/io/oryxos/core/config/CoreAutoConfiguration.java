package io.oryxos.core.config;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentStore;
import io.oryxos.core.agent.WorkspaceWatcher;
import io.oryxos.core.audit.AgentRunAuditor;
import io.oryxos.core.audit.NoopAgentRunAuditor;
import io.oryxos.core.audit.NoopToolInvocationAuditor;
import io.oryxos.core.audit.ToolInvocationAuditor;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.AgentLoader;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.react.PromptBuilder;
import io.oryxos.core.react.ReActLoop;
import io.oryxos.core.react.ToolExecutor;
import io.oryxos.core.schedule.AgentScheduler;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.session.SessionStore;
import io.oryxos.core.tool.NoopToolInvocationValidator;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolInvocationValidator;
import io.oryxos.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;
import java.util.List;

/**
 * core 引擎层的 Spring 装配。
 *
 * <p>只使用 spring-context 的注解，不引入 Spring Boot 依赖——core 保持为可独立使用的库。</p>
 *
 * <p>可选协作者（LLM 客户端、审计、校验器、记忆、会话存储、Profile 注册表）一律用
 * {@link ObjectProvider} 注入：存在即用真实实现，缺失则回退到安全默认（或给出清晰错误）。</p>
 */
@Configuration
public class CoreAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(CoreAutoConfiguration.class);

    private static final String DEFAULT_WORKSPACE = "${user.dir}/.oryxos";

    @Bean
    public ContextLoader contextLoader(@Value("${oryxos.workspace:" + DEFAULT_WORKSPACE + "}") String workspace) {
        logger.info("[启动] ContextLoader 工作区: {}", workspace);
        return new ContextLoader(Path.of(workspace));
    }

    @Bean
    public ToolRegistry oryxosToolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    public ToolExecutor toolExecutor(ToolRegistry toolRegistry,
                                     ObjectProvider<ToolInvocationValidator> validatorProvider,
                                     ObjectProvider<ToolInvocationAuditor> auditorProvider) {
        ToolInvocationValidator validator =
                validatorProvider.getIfAvailable(() -> NoopToolInvocationValidator.INSTANCE);
        ToolInvocationAuditor auditor =
                auditorProvider.getIfAvailable(() -> NoopToolInvocationAuditor.INSTANCE);
        logger.info("[启动] ToolExecutor 装配完成（校验器: {}, 审计: {}）",
                validator.getClass().getSimpleName(), auditor.getClass().getSimpleName());
        return new ToolExecutor(toolRegistry, validator, auditor);
    }

    @Bean
    public PromptBuilder promptBuilder(ContextLoader contextLoader,
                                       ObjectProvider<MemoryService> memoryServiceProvider) {
        return new PromptBuilder(contextLoader, memoryServiceProvider.getIfAvailable());
    }

    @Bean
    public ReActLoop reActLoop(ObjectProvider<LlmClient> llmClientProvider,
                               ToolRegistry toolRegistry,
                               PromptBuilder promptBuilder,
                               ToolExecutor toolExecutor) {
        LlmClient llmClient = llmClientProvider.getIfAvailable();
        if (llmClient == null) {
            throw new IllegalStateException(
                    "未找到 LlmClient 实现：请确认 oryxos-provider 的 ProviderServiceLlmClient 已装配");
        }
        logger.info("[启动] ReActLoop 装配完成（LLM 客户端: {}）", llmClient.getClass().getSimpleName());
        return new ReActLoop(llmClient, toolRegistry, promptBuilder, toolExecutor);
    }

    @Bean
    public SessionManager sessionManager(ObjectProvider<SessionStore> sessionStoreProvider) {
        SessionStore store = sessionStoreProvider.getIfAvailable();
        logger.info("[启动] SessionManager 装配完成（持久化: {}）",
                store == null ? "内存（未接入存储）" : store.getClass().getSimpleName());
        return new SessionManager(store);
    }

    @Bean
    public AgentService agentService(ObjectProvider<ProfileRegistry> profileRegistryProvider,
                                     SessionManager sessionManager,
                                     ReActLoop reActLoop,
                                     ObjectProvider<AgentRunAuditor> runAuditorProvider) {
        ProfileRegistry registry = profileRegistryProvider.getIfAvailable();
        if (registry == null) {
            throw new IllegalStateException(
                    "未找到 ProfileRegistry：请确认 ProfileLoader 已装配（oryxos-provider 的 ProviderAutoConfiguration）");
        }
        AgentRunAuditor runAuditor =
                runAuditorProvider.getIfAvailable(() -> NoopAgentRunAuditor.INSTANCE);
        logger.info("[启动] AgentService 装配完成（请求级审计: {}）", runAuditor.getClass().getSimpleName());
        return new AgentService(registry, sessionManager, reActLoop, runAuditor);
    }

    /**
     * 定时任务的调度线程池。
     *
     * <p>池大小默认 2：调度线程只在触发瞬间被占用，真正耗时在模型调用；
     * 1 会让一个慢任务推迟其它任务的触发时刻，更大则在慢模型下堆出并发请求。</p>
     *
     * <p>{@code waitForTasksToCompleteOnShutdown=false}：关闭时不等待长任务跑完，
     * 与"重命令不阻塞退出"的既有取向一致；任务本身的可中断性由 ReAct 的超时机制兜底。</p>
     */
    @Bean
    public ThreadPoolTaskScheduler oryxosTaskScheduler(
            @Value("${oryxos.scheduler.pool-size:2}") int poolSize) {
        int size = Math.max(1, poolSize);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(size);
        scheduler.setThreadNamePrefix("oryxos-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(error -> logger.error("[Scheduler] 调度线程异常（不外抛）", error));
        scheduler.initialize();
        logger.info("[启动] 调度线程池装配完成（pool-size={}）", size);
        return scheduler;
    }

    /**
     * 第三种触发源：到点把 Profile 里声明的话交给同一个 {@link AgentService}。
     *
     * <p>依赖 {@link TaskScheduler} 抽象而非具体线程池，便于测试用替身抓注册参数。</p>
     */
    @Bean
    public AgentScheduler agentScheduler(TaskScheduler taskScheduler,
                                         ProfileRegistry profileRegistry,
                                         SessionManager sessionManager,
                                         AgentService agentService) {
        logger.info("[启动] AgentScheduler 装配完成（定时任务是第三种触发源，处理入口与 CLI/Web 相同）");
        return new AgentScheduler(taskScheduler, profileRegistry, sessionManager, agentService);
    }

    // ------------------------------------------------------------------
    // Agent 的动态管理（30 节）：目录存储 → 生命周期编排 → 目录监听
    // ------------------------------------------------------------------

    /**
     * Agent 目录的文件系统事实层。
     *
     * <p>{@code oryxos.author.provider/model} 一身两职：既是"生成 Agent 的模型"，
     * 也是"新建 Agent 模板里预填的 provider/model"。合成一份配置是有意的——
     * 两处都要求"这个实例至少有一个声明过的 provider"，多一套配置只会多一个忘记配的地方。</p>
     */
    @Bean
    public AgentStore agentStore(@Value("${oryxos.workspace:" + DEFAULT_WORKSPACE + "}") String workspace,
                                 @Value("${oryxos.author.provider:}") String authorProvider,
                                 @Value("${oryxos.author.model:}") String authorModel) {
        AgentStore store = new AgentStore(Path.of(workspace), authorProvider, authorModel);
        logger.info("[启动] AgentStore 装配完成（agents: {}，模板 provider: {}）",
                store.agentsDir(), store.defaultProvider() == null ? "<未配置>" : store.defaultProvider());
        return store;
    }

    /**
     * 生命周期编排：注册 / 创建 / 更新 / 删除 / 生成 / 保存。
     *
     * <p>{@code AgentLoader} 由 provider 模块装配（它需要全局 provider 名单），
     * 这里用 {@link ObjectProvider} 取，缺失时给出明确错误而不是空指针。</p>
     */
    @Bean
    @DependsOn("agentLoader")
    public AgentLifecycleService agentLifecycleService(AgentStore agentStore,
                                                       ObjectProvider<AgentLoader> agentLoaderProvider,
                                                       ObjectProvider<ProfileRegistry> profileRegistryProvider,
                                                       ObjectProvider<AgentScheduler> agentSchedulerProvider,
                                                       ObjectProvider<LlmClient> llmClientProvider,
                                                       ObjectProvider<ToolRegistry> toolRegistryProvider,
                                                       @Value("${oryxos.author.provider:}") String authorProvider,
                                                       @Value("${oryxos.author.model:}") String authorModel) {
        AgentLoader loader = agentLoaderProvider.getIfAvailable();
        if (loader == null) {
            throw new IllegalStateException(
                    "未找到 AgentLoader：请确认 oryxos-provider 的 ProviderAutoConfiguration 已装配");
        }
        ProfileRegistry profileRegistry = profileRegistryProvider.getIfAvailable();
        if (profileRegistry == null) {
            throw new IllegalStateException(
                    "未找到 ProfileRegistry：请确认 ProfileLoader 已装配（oryxos-provider 的 ProviderAutoConfiguration）");
        }
        AgentScheduler agentScheduler = agentSchedulerProvider.getIfAvailable();
        if (agentScheduler == null) {
            throw new IllegalStateException("未找到 AgentScheduler：定时任务与生命周期编排无法装配");
        }
        ToolRegistry toolRegistry = toolRegistryProvider.getIfAvailable();
        List<String> toolNames = toolRegistry == null
                ? List.of()
                : toolRegistry.listAll().stream().map(OryxTool::getName).sorted().toList();

        logger.info("[启动] AgentLifecycleService 装配完成（生成模型: {} / {}，可用工具 {} 个）",
                authorProvider.isBlank() ? "<未配置>" : authorProvider,
                authorModel.isBlank() ? "<未配置>" : authorModel,
                toolNames.size());
        return new AgentLifecycleService(agentStore, loader, profileRegistry, agentScheduler,
                llmClientProvider.getIfAvailable(), authorProvider, authorModel, toolNames);
    }

    /**
     * 目录监听：新增 / 修改 / 删除目录后免重启生效。
     *
     * <p>{@link WorkspaceWatcher} 在 {@code afterPropertiesSet} 里起线程。依赖链保证顺序：
     * watcher → lifecycle → agentLoader（已扫过一遍）→ profileLoader。启动时它只补注册监听键，
     * 不回调业务，因此不会出现同一 Agent 注册两次（双份 cron）。</p>
     */
    @Bean
    public WorkspaceWatcher workspaceWatcher(AgentStore agentStore, AgentLifecycleService lifecycle) {
        return new WorkspaceWatcher(agentStore.agentsDir(), lifecycle);
    }
}
