package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * 基于既有 SDK 的连接实现：支持本地子进程与远程两种方式。
 *
 * <p>这是整个外部服务接入里<b>唯一</b>直接依赖协议 SDK 的类。把它独立出来有两个好处：
 * 单测可以注入替身，不必起真实进程或真实网络；将来若要更换底层实现，
 * 改动范围限定在这里与适配器，不影响注册、授权、清单等上层逻辑。</p>
 *
 * <p>超时是必须显式设置的：外部服务卡住时，不能把 Agent 的一轮处理一起拖死。</p>
 */
public class SdkMcpClientProvider implements McpClientProvider {

    private static final Logger logger = LoggerFactory.getLogger(SdkMcpClientProvider.class);

    /** 单次请求上限。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 握手（初始化）上限。
     *
     * <p>取值偏宽松（与请求上限相同）是<b>实测决定的</b>：本地子进程方式最常见的写法是经包管理器的
     * 转发命令启动（例如 {@code npx -y <包名>}），首次运行需要下载包。实测一个演示用服务
     * 冷启动耗时 21.5 秒，预热后 1.3 秒——按"尽快暴露故障"的思路取 15 秒，
     * 会让所有新包在第一次连接时必然超时，而使用者只会看到"连接失败"，无从知道是拉包慢。</p>
     *
     * <p>代价：服务真的不可用时，每次启动要多等这么久。好在这只影响启动耗时，
     * 失败本身会被隔离成一条告警，不影响其它服务与内置工具。</p>
     */
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public McpSyncClient connect(McpServerDeclaration declaration) {
        McpClientTransport transport = declaration.isStdio()
                ? stdioTransport(declaration)
                : new HttpClientSseClientTransport(declaration.url());

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(INITIALIZATION_TIMEOUT)
                .build();

        try {
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            // 握手失败也要回收：否则会留下一个悬挂的外部子进程
            closeQuietly(client, declaration.name());
            throw e;
        }
    }

    private McpClientTransport stdioTransport(McpServerDeclaration declaration) {
        ServerParameters.Builder builder = ServerParameters.builder(declaration.command())
                .args(declaration.argsOrEmpty());
        Map<String, String> env = declaration.envOrEmpty();
        if (!env.isEmpty()) {
            builder.env(env);
        }
        return new StdioClientTransport(builder.build(), objectMapper);
    }

    private static void closeQuietly(McpSyncClient client, String serverName) {
        try {
            client.close();
        } catch (RuntimeException e) {
            logger.warn("[Mcp] 握手失败后的连接回收未成功: name={}, error={}", serverName, e.getMessage());
        }
    }
}
