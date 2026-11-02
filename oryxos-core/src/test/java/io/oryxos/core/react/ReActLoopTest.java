package io.oryxos.core.react;

import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.session.Session;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReActLoop 验收测试：无工具一轮收尾、有工具执行并回填、每轮累积、轮数兜底、整体耗时上限。
 */
class ReActLoopTest {

    @TempDir
    Path workspace;

    private LlmClient llmClient;
    private ToolRegistry registry;
    private AtomicInteger toolExecutions;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        registry = new ToolRegistry();
        toolExecutions = new AtomicInteger();
        registry.register(countingTool("http_get"));
    }

    private OryxTool countingTool(String name) {
        return new OryxTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "计数工具";
            }

            @Override
            public String getInputSchema() {
                return "{\"type\":\"object\",\"properties\":{}}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                toolExecutions.incrementAndGet();
                return ToolResult.ok("天气：晴 25℃");
            }
        };
    }

    private Profile profile(Map<String, Object> settings) {
        Profile profile = new Profile();
        profile.setName("ops-agent");
        profile.setIdentity("你是运维助手");
        profile.setSettings(settings);
        return profile;
    }

    private Session session() {
        return new Session("s-1", "ops-agent", "cli", "u-1");
    }

    private ReActLoop loop() {
        PromptBuilder promptBuilder = new PromptBuilder(new ContextLoader(workspace), null);
        ToolExecutor toolExecutor = new ToolExecutor(registry, null, null);
        return new ReActLoop(llmClient, registry, promptBuilder, toolExecutor);
    }

    private LlmClient.LlmResponse text(String text) {
        return new LlmClient.LlmResponse(text, List.of(), null);
    }

    private LlmClient.LlmResponse toolCall(String name, String arguments) {
        return new LlmClient.LlmResponse(null, List.of(new LlmClient.ToolCall(name, arguments)), null);
    }

    @Test
    void 无工具调用_一轮收尾() {
        when(llmClient.call(anyString(), any(), any())).thenReturn(text("北京今天晴，建议穿短袖"));
        Session session = session();

        String reply = loop().run("s-1", session, "北京天气如何", profile(Map.of()));

        assertThat(reply).isEqualTo("北京今天晴，建议穿短袖");
        verify(llmClient, times(1)).call(anyString(), any(), any());
        assertThat(session.getMessages()).hasSize(2);
        assertThat(session.getMessages().get(0).getRole()).isEqualTo("user");
        assertThat(session.getMessages().get(1).getRole()).isEqualTo("assistant");
    }

    @Test
    void 有工具调用_执行并把结果回填进下一轮() {
        when(llmClient.call(anyString(), any(), any()))
                .thenReturn(toolCall("http_get", "{\"url\":\"http://weather\"}"))
                .thenReturn(text("晴，建议穿短袖"));
        Session session = session();

        String reply = loop().run("s-1", session, "北京天气如何", profile(Map.of()));

        assertThat(reply).isEqualTo("晴，建议穿短袖");
        assertThat(toolExecutions.get()).isEqualTo(1);
        verify(llmClient, times(2)).call(anyString(), any(), any());

        // 每轮的响应与工具结果都累积进会话（坑三回归）
        List<Session.Message> messages = session.getMessages();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getContent()).contains("[Tool Request]").contains("http_get");
        assertThat(messages.get(2).getRole()).isEqualTo("tool");
        assertThat(messages.get(2).getContent()).contains("晴 25℃");
        assertThat(messages.get(3).getContent()).isEqualTo("晴，建议穿短袖");
    }

    @Test
    void 模型一直要调工具_转满最大轮数强制停() {
        when(llmClient.call(anyString(), any(), any())).thenReturn(toolCall("http_get", "{}"));
        Session session = session();

        String reply = loop().run("s-1", session, "查天气", profile(Map.of()));

        verify(llmClient, times(Profile.DEFAULT_MAX_ITERATIONS)).call(anyString(), any(), any());
        assertThat(reply).contains("已达到最大轮数");
    }

    @Test
    void Profile覆盖轮数上限_按覆盖值停止() {
        when(llmClient.call(anyString(), any(), any())).thenReturn(toolCall("http_get", "{}"));

        String reply = loop().run("s-1", session(), "查天气",
                profile(Map.of("max_iterations", 3)));

        verify(llmClient, times(3)).call(anyString(), any(), any());
        assertThat(reply).contains("已达到最大轮数（3）");
    }

    @Test
    void 模型既无文本也无工具请求_以明确提示收尾() {
        when(llmClient.call(anyString(), any(), any())).thenReturn(new LlmClient.LlmResponse(null, List.of(), null));

        String reply = loop().run("s-1", session(), "你好", profile(Map.of()));

        assertThat(reply).isEqualTo(ReActLoop.EMPTY_RESPONSE_MESSAGE);
    }

    @Test
    void 模型调用失败_包成ModelCallException上抛且保留原始原因() {
        when(llmClient.call(anyString(), any(), any())).thenThrow(new RuntimeException("connect timeout"));

        assertThatThrownBy(() -> loop().run("s-1", session(), "你好", profile(Map.of())))
                .isInstanceOf(ModelCallException.class)
                .hasMessageContaining("connect timeout")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void 超过整体耗时上限_抛ProcessingTimeoutException() {
        AtomicLong now = new AtomicLong(0);
        when(llmClient.call(anyString(), any(), any())).thenAnswer(invocation -> {
            now.addAndGet(120_000);   // 模拟单次调用就耗掉 2 分钟
            return toolCall("http_get", "{}");
        });
        PromptBuilder promptBuilder = new PromptBuilder(new ContextLoader(workspace), null);
        ToolExecutor toolExecutor = new ToolExecutor(registry, null, null);
        ReActLoop loop = new ReActLoop(llmClient, registry, promptBuilder, toolExecutor, now::get);

        assertThatThrownBy(() -> loop.run("s-1", session(), "查天气", profile(Map.of())))
                .isInstanceOf(ProcessingTimeoutException.class)
                .hasMessageContaining("60");
    }

    @Test
    void 超时异常_携带已完成的轮数与工具次数() {
        AtomicLong now = new AtomicLong(0);
        when(llmClient.call(anyString(), any(), any())).thenAnswer(invocation -> {
            now.addAndGet(120_000);
            return toolCall("http_get", "{}");
        });
        PromptBuilder promptBuilder = new PromptBuilder(new ContextLoader(workspace), null);
        ToolExecutor toolExecutor = new ToolExecutor(registry, null, null);
        ReActLoop loop = new ReActLoop(llmClient, registry, promptBuilder, toolExecutor, now::get);

        assertThatThrownBy(() -> loop.runResult("s-1", session(), "查天气", profile(Map.of())))
                .isInstanceOf(ProcessingTimeoutException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(ProcessingTimeoutException.class))
                .satisfies(e -> {
                    // 失败也要留下真实进度，供请求级审计记录
                    assertThat(e.getCompletedIterations()).isEqualTo(1);
                    assertThat(e.getToolCallCount()).isEqualTo(1);
                    assertThat(e.getTimeoutSeconds()).isEqualTo(60);
                });
    }

    @Test
    void runResult_正常答复_带停止原因与轮数() {
        when(llmClient.call(anyString(), any(), any())).thenReturn(text("答复"));

        ReActResult result = loop().runResult("s-1", session(), "你好", profile(Map.of()));

        assertThat(result.text()).isEqualTo("答复");
        assertThat(result.stopReason()).isEqualTo(StopReason.FINAL_ANSWER);
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.toolCallCount()).isZero();
        assertThat(result.success()).isTrue();
    }

    @Test
    void runResult_含工具调用_累计工具次数与token() {
        when(llmClient.call(anyString(), any(), any()))
                .thenReturn(new LlmClient.LlmResponse(null,
                        List.of(new LlmClient.ToolCall("http_get", "{}")),
                        new LlmClient.TokenUsage(10, 5, 15)))
                .thenReturn(new LlmClient.LlmResponse("晴",
                        List.of(), new LlmClient.TokenUsage(20, 8, 28)));

        ReActResult result = loop().runResult("s-1", session(), "查天气", profile(Map.of()));

        assertThat(result.stopReason()).isEqualTo(StopReason.FINAL_ANSWER);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.totalUsage().promptTokens()).isEqualTo(30);
        assertThat(result.totalUsage().completionTokens()).isEqualTo(13);
        assertThat(result.totalUsage().totalTokens()).isEqualTo(43);
    }

    @Test
    void runResult_达到最大轮数_停止原因可与正常答复区分() {
        when(llmClient.call(anyString(), any(), any())).thenReturn(toolCall("http_get", "{}"));

        ReActResult result = loop().runResult("s-1", session(), "查天气", profile(Map.of()));

        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_ITERATIONS);
        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("已达到最大轮数");
    }

    @Test
    void runResult_空响应_停止原因可与正常答复区分() {
        when(llmClient.call(anyString(), any(), any()))
                .thenReturn(new LlmClient.LlmResponse(null, List.of(), null));

        ReActResult result = loop().runResult("s-1", session(), "你好", profile(Map.of()));

        assertThat(result.stopReason()).isEqualTo(StopReason.EMPTY_RESPONSE);
        assertThat(result.success()).isFalse();
        assertThat(result.text()).isEqualTo(ReActLoop.EMPTY_RESPONSE_MESSAGE);
    }
}
