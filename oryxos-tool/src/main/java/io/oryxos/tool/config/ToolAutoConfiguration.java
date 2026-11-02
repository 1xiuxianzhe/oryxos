package io.oryxos.tool.config;

import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.builtin.FileTool;
import io.oryxos.tool.builtin.HttpTool;
import io.oryxos.tool.builtin.MockWeatherTool;
import io.oryxos.tool.builtin.ShellTool;
import io.oryxos.tool.mcp.McpClientProvider;
import io.oryxos.tool.mcp.McpClientService;
import io.oryxos.tool.mcp.McpServerConfigLoader;
import io.oryxos.tool.mcp.SdkMcpClientProvider;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.nio.file.Path;
import java.util.List;

/**
 * 工具体系装配：白名单校验器 + 内置工具登记。
 *
 * <p><b>为什么两者必须一起交付</b>：内置工具（文件/命令/HTTP）**强依赖非空的白名单校验器**，
 * 校验器缺失时工具根本构造不出来；而校验器在清单为空时"一律拒绝"。
 * 因此不存在"先注册工具、下轮再配校验器"的中间状态。</p>
 *
 * <p>工具一律"在装配处显式登记"，不走注解扫描与自动执行（宪法 III：Spring AI 只做协议转换与
 * schema 生成，不做循环引擎，也不做自动 tool 执行）。</p>
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class ToolAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ToolAutoConfiguration.class);

    /** 文件类内置工具名：同一实现类由名字区分三种操作。 */
    static final List<String> FILE_TOOL_NAMES = List.of("read_file", "write_file", "list_dir");

    /** 与 ContextLoader 保持一致的工作区默认值。 */
    private static final String DEFAULT_WORKSPACE = "${user.dir}/.oryxos";

    /**
     * 沙箱 Bean（唯一实现：{@link WhitelistSandbox}）。
     *
     * <p>产出类型刻意是接口 {@link Sandbox}：四个 Tool 注入的静态类型因此只能是抽象，
     * 换隔离档时只换这里的实现类。这也是"沙箱强制装配"的落点——运行期不存在没有沙箱的状态。</p>
     *
     * <p>三个清单为空时仍然产出实例——由实现自身"空即拒绝"。这里只拦**格式非法**的配置。</p>
     *
     * <p><b>关闭开关</b>（{@code oryxos.sandbox.enabled=false}）会让三个维度全部放行。
     * 这是唯一一处"关闭安全边界"的地方，因此：默认值是 {@code true}（漏配不会导致敞开），
     * 关闭时打醒目告警（这条配置最怕"忘了改回来"）。</p>
     *
     * <p>注意：配置格式校验<b>始终</b>执行，不受开关影响——空白条目是配置缺陷，
     * 与当前是否启用无关，越早发现越好。</p>
     */
    @Bean
    public Sandbox sandbox(SandboxProperties properties) {
        requireNoBlankEntries("allowed-paths", properties.getAllowedPaths());
        requireNoBlankEntries("allowed-commands", properties.getAllowedCommands());
        requireNoBlankEntries("allowed-domains", properties.getAllowedDomains());
        requireNoBlankEntries("allowed-interpreters", properties.getAllowedInterpreters());

        if (!properties.isEnabled()) {
            logger.warn("================================================================");
            logger.warn("[启动] 出站白名单【已关闭】(oryxos.sandbox.enabled=false)");
            logger.warn("[启动] 文件读写、命令执行、HTTP 请求不做任何校验，一律放行。");
            logger.warn("[启动] 该状态仅适用于本地开发与调试，切勿带到生产环境。");
            logger.warn("================================================================");
            return new WhitelistSandbox(
                    properties.getAllowedPaths(),
                    properties.getAllowedCommands(),
                    properties.getAllowedDomains(),
                    properties.getAllowedInterpreters(),
                    false);
        }

        Sandbox sandbox = new WhitelistSandbox(
                properties.getAllowedPaths(),
                properties.getAllowedCommands(),
                properties.getAllowedDomains(),
                properties.getAllowedInterpreters(),
                true);
        logger.info("[启动] 沙箱装配完成（WhitelistSandbox：路径 {} 条、命令 {} 条、域名 {} 条、解释器 {} 条）",
                properties.getAllowedPaths().size(),
                properties.getAllowedCommands().size(),
                properties.getAllowedDomains().size(),
                properties.getAllowedInterpreters().size());
        return sandbox;
    }

    /**
     * 把六个内置工具登记进共享注册表。
     *
     * <p>刻意用一个专门的登记器 Bean，而不是再声明一个 {@code ToolRegistry} 类型的 Bean——
     * 后者会与 {@code CoreAutoConfiguration} 已有的那个冲突，使按类型注入处出现歧义。</p>
     *
     * <p>外部工具服务的装配会依赖本 Bean，以保证"内置先注册、撞名时内置胜出"这一顺序不变式。</p>
     */
    @Bean
    public BuiltinToolRegistration builtinToolRegistration(ToolRegistry toolRegistry,
                                                          Sandbox sandbox) {
        return new BuiltinToolRegistration(toolRegistry, sandbox);
    }

    /**
     * 登记<b>演示用</b>的虚拟天气工具 {@code get_weather}。
     *
     * <p>刻意独立成一个登记器，不塞进 {@link BuiltinToolRegistration}：内置工具是地基，
     * 数量与内容是被断言过的；演示工具随时可能被删掉，混进去会让"删一个演示工具"
     * 看起来像改动内核。</p>
     *
     * <p>该工具不触达文件/命令/外网，因此不需要 {@link Sandbox} 注入。</p>
     */
    @Bean
    public WeatherToolRegistration weatherToolRegistration(ToolRegistry toolRegistry) {
        return new WeatherToolRegistration(toolRegistry);
    }

    /**
     * 拦截会**静默放宽**白名单的配置。
     *
     * <p>空白条目是最隐蔽的一种：空字符串路径会被规范化成当前工作目录，
     * 于是"我配了个空值"变成了"我允许了工作目录下的所有文件"。这种错必须启动即报，
     * 不能留到第一次工具调用时才以奇怪的方式表现。</p>
     */
    private static void requireNoBlankEntries(String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        boolean hasBlank = values.stream().anyMatch(value -> value == null || value.isBlank());
        if (hasBlank) {
            throw new IllegalStateException(
                    "oryxos.sandbox." + key + " 含空白条目，会意外放宽白名单，请在 application.yaml 中移除后重启");
        }
    }

    /**
     * 外部工具服务的装配。
     *
     * <p>{@code @DependsOn} 内置登记器不是可有可无的：注册表在重名时"先到先得"，
     * 必须保证内置工具先注册，撞名时才是内置胜出。</p>
     */
    @Bean
    @DependsOn("builtinToolRegistration")
    public McpClientService mcpClientService(ToolRegistry toolRegistry,
                                            McpClientProvider clientProvider,
                                            McpServerConfigLoader configLoader) {
        return new McpClientService(toolRegistry, clientProvider, configLoader);
    }

    /** 工作区声明文件的读取器；与 ContextLoader 使用同一个工作区属性。 */
    @Bean
    public McpServerConfigLoader mcpServerConfigLoader(
            @Value("${oryxos.workspace:" + DEFAULT_WORKSPACE + "}") String workspace) {
        return new McpServerConfigLoader(Path.of(workspace));
    }

    @Bean
    public McpClientProvider mcpClientProvider() {
        return new SdkMcpClientProvider();
    }

    /** 仅承担"把内置工具挂进注册表"这一个副作用。 */
    public static class BuiltinToolRegistration implements InitializingBean {

        private static final Logger logger = LoggerFactory.getLogger(BuiltinToolRegistration.class);

        private final ToolRegistry toolRegistry;
        private final Sandbox sandbox;

        BuiltinToolRegistration(ToolRegistry toolRegistry, Sandbox sandbox) {
            this.toolRegistry = toolRegistry;
            this.sandbox = sandbox;
        }

        @Override
        public void afterPropertiesSet() {
            FILE_TOOL_NAMES.forEach(name -> toolRegistry.register(new FileTool(name, sandbox)));
            toolRegistry.register(new ShellTool(sandbox));
            toolRegistry.register(new HttpTool("http_get", sandbox));
            toolRegistry.register(new HttpTool("http_post", sandbox));

            logger.info("[启动] 已注册内置工具，当前注册表: {}",
                    toolRegistry.listAll().stream().map(OryxTool::getName).toList());
        }
    }

    /** 仅承担"把演示用虚拟天气工具挂进注册表"这一个副作用。 */
    public static class WeatherToolRegistration implements InitializingBean {

        private static final Logger logger = LoggerFactory.getLogger(WeatherToolRegistration.class);

        private final ToolRegistry toolRegistry;

        WeatherToolRegistration(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
        }

        @Override
        public void afterPropertiesSet() {
            toolRegistry.register(new MockWeatherTool());
            logger.info("[启动] 已注册演示用虚拟天气工具: {}（不联网，随机返回；当前注册表共 {} 个）",
                    MockWeatherTool.NAME, toolRegistry.listAll().size());
        }
    }
}
