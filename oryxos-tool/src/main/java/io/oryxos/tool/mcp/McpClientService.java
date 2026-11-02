package io.oryxos.tool.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部工具服务的生命周期管理：启动期连接全部、包装注册、失联隔离、停机回收。
 *
 * <p><b>失联隔离是这里最重要的行为</b>：某个服务没起来、连不上、握手失败，
 * 都必须只产生一条可定位的告警并跳过它自己，绝不影响应用启动，也绝不影响
 * 其它服务与内置工具的注册。外部依赖的可用性不等于自身的可用性。</p>
 *
 * <p>注册顺序由装配保证（本 Bean 依赖内置工具登记器）：注册表在重名时"先到先得"，
 * 因此撞名时<b>内置工具胜出</b>，被拒的外部工具会有告警，避免撞名无声发生。</p>
 */
public class McpClientService implements InitializingBean, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(McpClientService.class);

    private final ToolRegistry toolRegistry;
    private final McpClientProvider clientProvider;
    private final McpServerConfigLoader configLoader;

    /** 已建立的连接，停机时统一回收，避免遗留悬挂的外部子进程。 */
    private final List<McpSyncClient> connectedClients = new ArrayList<>();

    public McpClientService(ToolRegistry toolRegistry,
                            McpClientProvider clientProvider,
                            McpServerConfigLoader configLoader) {
        this.toolRegistry = toolRegistry;
        this.clientProvider = clientProvider;
        this.configLoader = configLoader;
    }

    @Override
    public void afterPropertiesSet() {
        connectAll();
    }

    /**
     * 连接全部已声明的外部服务并注册其工具。
     *
     * <p>逐条独立处理：一条失败不影响后续条目的尝试。</p>
     */
    public void connectAll() {
        List<McpServerDeclaration> declarations = configLoader.load();
        if (declarations.isEmpty()) {
            logger.info("[Mcp] 未声明外部工具服务，外部工具数为 0");
            return;
        }
        int succeeded = 0;
        for (McpServerDeclaration declaration : declarations) {
            if (connectOne(declaration)) {
                succeeded++;
            }
        }
        logger.info("[Mcp] 外部工具服务接入完成: 成功 {} / 共 {}", succeeded, declarations.size());
    }

    private boolean connectOne(McpServerDeclaration declaration) {
        long beganAt = System.currentTimeMillis();
        try {
            McpSyncClient client = clientProvider.connect(declaration);
            connectedClients.add(client);

            McpSchema.ListToolsResult listed = client.listTools();
            List<McpSchema.Tool> tools = listed == null || listed.tools() == null
                    ? List.of()
                    : listed.tools();
            for (McpSchema.Tool tool : tools) {
                toolRegistry.register(new McpToolAdapter(client, tool));
            }
            logger.info("[Mcp] 已接入外部工具服务: name={}, 工具 {} 个, 耗时 {}ms, 注册表共 {} 个",
                    declaration.name(), tools.size(), System.currentTimeMillis() - beganAt,
                    toolRegistry.listAll().size());
            return true;
        } catch (RuntimeException e) {
            // 外部依赖失联不拖垮自身启动：只告警、跳过该服务，继续下一条。
            //
            // 这里的异常几乎总是"握手超时"，而不是真实原因：命令无法启动之类的问题
            // 会被底层丢到后台线程打成 ERROR（形如 "Failed to start process with command"），
            // 不会出现在我们捕获的异常里。所以必须把线索指出来，
            // 否则使用者面对一个拼错的命令只能看到"莫名超时"，无从下手。
            long waitedMs = System.currentTimeMillis() - beganAt;
            logger.warn("[Mcp] 外部工具服务连接失败，已跳过: name={}, 等待 {}ms, error={}",
                    declaration.name(), waitedMs, e.getMessage());
            logger.warn("[Mcp] 排查提示（{}）：① 命令拼错/不可执行时，日志中会有 "
                    + "\"Failed to start process with command\"，那才是真实原因；"
                    + "② 命令经包管理器转发（如 npx）时首次运行需下载包，"
                    + "可先手动执行一次该命令预热；③ 服务没起来时请确认它已在运行。",
                    declaration.name());
            return false;
        }
    }

    @Override
    public void close() {
        for (McpSyncClient client : connectedClients) {
            try {
                client.close();
            } catch (RuntimeException e) {
                logger.warn("[Mcp] 关闭外部服务连接失败: error={}", e.getMessage());
            }
        }
        connectedClients.clear();
        logger.info("[Mcp] 已关闭全部外部工具服务连接");
    }
}
