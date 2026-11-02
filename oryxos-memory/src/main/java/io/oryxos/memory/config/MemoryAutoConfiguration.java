package io.oryxos.memory.config;

import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.LongTermMemory;
import io.oryxos.memory.MemoryFiles;
import io.oryxos.memory.MemoryLocator;
import io.oryxos.memory.MemoryTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Memory 能力的装配：长期记忆文件 + 统一门面 + 两个记忆工具。
 *
 * <p>与 {@code ToolAutoConfiguration} 的分工一致：能力模块自带装配，{@code oryxos-boot} 负责组装。
 * 门面实现放在这里，是因为 core 不能依赖本模块——core 只持有接口。</p>
 *
 * <p>工具一律<b>显式登记</b>进共享注册表，不走注解扫描（宪法 III）。</p>
 */
@Configuration
public class MemoryAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    /** 与 ContextLoader、McpServerConfigLoader 保持一致的工作区默认值。 */
    private static final String DEFAULT_WORKSPACE = "${user.dir}/.oryxos";

    /**
     * 长期记忆：文件式，路径与初始化命令共用 {@link MemoryFiles} 的约定。
     *
     * <p>刻意<b>不</b>在启动时创建文件：记忆文件属于工作区数据，由 {@code oryxos init} 负责；
     * 首次写入会自动补齐目录与骨架，因此"没 init 过也能记住事"。</p>
     */
    @Bean
    public LongTermMemory longTermMemory(@Value("${oryxos.workspace:" + DEFAULT_WORKSPACE + "}") String workspace) {
        Path file = MemoryFiles.resolve(Path.of(workspace));
        logger.info("[启动] 长期记忆文件: {}", file);
        return new LongTermMemory(file);
    }

    /**
     * 记忆定位器：目录 Agent 用自己的 {@code <agentDir>/memory/MEMORY.md}，
     * 其余情况回退到工作区记忆。
     */
    @Bean
    public MemoryLocator memoryLocator(@Value("${oryxos.workspace:" + DEFAULT_WORKSPACE + "}") String workspace) {
        Path workspaceRoot = Path.of(workspace);
        logger.info("[启动] 工作区共享记忆: {}", MemoryFiles.resolve(workspaceRoot));
        return new MemoryLocator(workspaceRoot);
    }

    /** 统一门面：引擎只认它（宪法原则 V）。 */
    @Bean
    public MemoryService memoryService(MemoryLocator memoryLocator) {
        logger.info("[启动] MemoryService 装配完成（长期记忆: 文件式 + 关键词检索，按 Agent 隔离）");
        return new DefaultMemoryService(memoryLocator);
    }

    /** 把两个记忆工具挂进共享注册表。 */
    @Bean
    public MemoryToolRegistration memoryToolRegistration(ToolRegistry toolRegistry,
                                                        MemoryService memoryService) {
        return new MemoryToolRegistration(toolRegistry, memoryService);
    }

    /** 仅承担"把记忆工具挂进注册表"这一个副作用。 */
    public static class MemoryToolRegistration implements InitializingBean {

        private final ToolRegistry toolRegistry;
        private final MemoryService memoryService;

        MemoryToolRegistration(ToolRegistry toolRegistry, MemoryService memoryService) {
            this.toolRegistry = toolRegistry;
            this.memoryService = memoryService;
        }

        @Override
        public void afterPropertiesSet() {
            toolRegistry.register(new MemoryTools.SaveMemoryTool(memoryService));
            toolRegistry.register(new MemoryTools.RecallMemoryTool(memoryService));
            logger.info("[启动] 已注册记忆工具，当前注册表: {}",
                    toolRegistry.listAll().stream().map(OryxTool::getName).toList());
        }
    }
}
