package io.oryxos.boot;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringRuntimeLauncherTest {

    private static OryxTool tool(String name) {
        return new OryxTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "描述-" + name;
            }

            @Override
            public String getInputSchema() {
                return "{}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                return ToolResult.ok("ok");
            }
        };
    }

    @Test
    void chatChannel复用注入的Agent和Session() {
        SessionManager sessions = new SessionManager();
        AgentService agent = mock(AgentService.class);
        SpringRuntimeLauncher launcher = new SpringRuntimeLauncher(sessions, agent, new ToolRegistry());

        assertThat(launcher.chatChannel()).isNotNull();
    }

    @Test
    void 工具清单来自注入的注册表() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file"));
        registry.register(tool("notify"));
        SpringRuntimeLauncher launcher =
                new SpringRuntimeLauncher(new SessionManager(), mock(AgentService.class), registry);

        assertThat(launcher.registeredTools()).containsExactly("notify", "read_file");
    }

    @Test
    void 注册表为空时工具清单为空() {
        SpringRuntimeLauncher launcher = new SpringRuntimeLauncher(
                new SessionManager(), mock(AgentService.class), new ToolRegistry());

        assertThat(launcher.registeredTools()).isEmpty();
    }
}
