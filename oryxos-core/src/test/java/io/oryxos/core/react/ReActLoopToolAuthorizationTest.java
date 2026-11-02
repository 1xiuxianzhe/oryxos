package io.oryxos.core.react;

import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.llm.LlmRequest;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.session.Session;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接线验证：ReAct 循环交给 Prompt 的工具必须是**按 Agent 授权过滤后**的那批，
 * 而不是注册表全量。
 *
 * <p>过滤语义本身由 {@code ToolResolverTest} 覆盖；这里只看"有没有真的接上"——
 * 若哪天有人把解析改回全量列举，本用例会立刻红。</p>
 */
class ReActLoopToolAuthorizationTest {

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
                return "{\"type\":\"object\",\"properties\":{}}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                return ToolResult.ok("ok");
            }
        };
    }

    private static Profile agentWithTools(String... declaredTools) {
        Profile profile = new Profile();
        profile.setName("ops-agent");
        profile.setTools(Arrays.stream(declaredTools)
                .map(name -> {
                    Profile.ToolConfig config = new Profile.ToolConfig();
                    config.setName(name);
                    return config;
                })
                .toList());
        return profile;
    }

    @Test
    void 只把被授权的工具交给本轮prompt() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file"));
        registry.register(tool("shell"));
        registry.register(tool("http_get"));

        LlmClient llmClient = mock(LlmClient.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        when(promptBuilder.build(any(), any(), any())).thenReturn(mock(LlmRequest.class));
        when(llmClient.call(any(), any(), any()))
                .thenReturn(new LlmClient.LlmResponse("答复", List.of(), null));

        ReActLoop loop = new ReActLoop(llmClient, registry, promptBuilder, toolExecutor);
        Session session = new Session("session-1", "ops-agent", "cli", "u-1");
        Profile profile = agentWithTools("shell");

        loop.run("session-1", session, "跑一下", profile);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OryxTool>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(promptBuilder).build(any(), any(), toolsCaptor.capture());

        assertThat(toolsCaptor.getValue()).extracting(OryxTool::getName).containsExactly("shell");
    }

    @Test
    void 未声明工具清单时把注册表全部交给本轮prompt() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file"));
        registry.register(tool("shell"));

        LlmClient llmClient = mock(LlmClient.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        when(promptBuilder.build(any(), any(), any())).thenReturn(mock(LlmRequest.class));
        when(llmClient.call(any(), any(), any()))
                .thenReturn(new LlmClient.LlmResponse("答复", List.of(), null));

        ReActLoop loop = new ReActLoop(llmClient, registry, promptBuilder, toolExecutor);
        Session session = new Session("session-2", "ops-agent", "cli", "u-1");
        Profile profile = new Profile();
        profile.setName("ops-agent");

        loop.run("session-2", session, "跑一下", profile);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OryxTool>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(promptBuilder).build(any(), any(), toolsCaptor.capture());

        assertThat(toolsCaptor.getValue()).extracting(OryxTool::getName)
                .containsExactlyInAnyOrder("read_file", "shell");
    }
}
