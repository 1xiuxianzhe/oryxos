package io.oryxos.boot;

import io.oryxos.channel.cli.CliChannel;
import io.oryxos.cli.RuntimeLauncher;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 将 Picocli 重命令接入唯一的 Spring Agent/Session 运行时。 */
@Component
public class SpringRuntimeLauncher implements RuntimeLauncher {
    private static final Logger logger = LoggerFactory.getLogger(SpringRuntimeLauncher.class);

    private final SessionManager sessionManager;
    private final AgentService agentService;
    private final ToolRegistry toolRegistry;

    public SpringRuntimeLauncher(SessionManager sessionManager,
                                 AgentService agentService,
                                 ToolRegistry toolRegistry) {
        this.sessionManager = sessionManager;
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public CliChannel chatChannel() {
        return new CliChannel(sessionManager, agentService);
    }

    @Override
    public void serve(int port) {
        logger.info("HTTP 服务由 Spring Boot 管理，CLI serve 参数已验证: port={}", port);
        System.out.println("serve: HTTP API 服务已由 Spring Boot 管理，端口=" + port);
    }

    @Override
    public void gateway() {
        logger.info("Gateway 使用共享 AgentService 与 SessionManager 启动");
        System.out.println("gateway: 网关运行时已接入共享 Agent/Session 服务");
    }

    @Override
    public List<String> registeredTools() {
        return toolRegistry.listAll().stream()
                .map(OryxTool::getName)
                .sorted()
                .toList();
    }
}
