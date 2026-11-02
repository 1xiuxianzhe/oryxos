package io.oryxos.provider;

import io.oryxos.core.audit.LlmCallAuditor;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProviderService 验收测试：双 provider 按名路由不串台、未知名抛异常、成功/失败都落审计、自动执行关闭。
 * 全部 mock ChatModel，不花一分钱、不依赖网络。
 */
class ProviderServiceTest {

    private final LlmCallAuditor auditor = mock(LlmCallAuditor.class);
    private final ToolSchemaAdapter adapter = new ToolSchemaAdapter();

    private static final Class<org.springframework.ai.chat.prompt.Prompt> SPRING_PROMPT =
            org.springframework.ai.chat.prompt.Prompt.class;

    private Profile profileUsing(String provider) {
        Profile profile = new Profile();
        profile.setName("test-profile");
        Profile.ProviderConfig providerConfig = new Profile.ProviderConfig();
        providerConfig.setName(provider);
        providerConfig.setModel(provider + "-model");
        providerConfig.setTemperature(0.7);
        profile.setProvider(providerConfig);
        return profile;
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatResponse responseWithUsage(String text, int prompt, int completion, int total) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(prompt, completion, total))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    @Test
    void 按名路由_两个provider不串台() {
        ChatModel deepseek = mock(ChatModel.class);
        ChatModel kimi = mock(ChatModel.class);
        when(kimi.call(any(SPRING_PROMPT))).thenReturn(response("kimi-answer"));
        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", deepseek, "kimi", kimi), adapter, auditor);

        LlmResponse result = service.chat("s-1", profileUsing("kimi"), Prompt.ofUserMessage("你好"));

        assertThat(result.text()).isEqualTo("kimi-answer");
        verify(kimi, times(1)).call(any(SPRING_PROMPT));
        verify(deepseek, never()).call(any(SPRING_PROMPT));
    }

    @Test
    void 未知名provider_抛出ProviderNotFoundException() {
        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", mock(ChatModel.class)), adapter, auditor);

        assertThatThrownBy(() -> service.chat("s-1", profileUsing("ghost"), Prompt.ofUserMessage("hi")))
                .isInstanceOf(ProviderNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void 调用失败_审计必须留下success为false的记录() {
        ChatModel deepseek = mock(ChatModel.class);
        when(deepseek.call(any(SPRING_PROMPT))).thenThrow(new RuntimeException("connect timeout"));
        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", deepseek), adapter, auditor);

        assertThrows(RuntimeException.class,
                () -> service.chat("s-1", profileUsing("deepseek"), Prompt.ofUserMessage("hi")));

        verify(auditor).record(eq("s-1"), eq("deepseek"), eq("deepseek-model"),
                isNull(), eq(false), contains("timeout"), anyLong());
    }

    @Test
    void 调用成功_审计记录success为true且带token用量() {
        ChatModel deepseek = mock(ChatModel.class);
        when(deepseek.call(any(SPRING_PROMPT))).thenReturn(responseWithUsage("answer", 1, 2, 3));
        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", deepseek), adapter, auditor);

        service.chat("s-1", profileUsing("deepseek"), Prompt.ofUserMessage("hi"));

        verify(auditor).record(eq("s-1"), eq("deepseek"), eq("deepseek-model"),
                any(TokenUsage.class), eq(true), isNull(), anyLong());
    }

    @Test
    void 带工具schema调用_请求里关闭了自动执行() {
        ChatModel deepseek = mock(ChatModel.class);
        when(deepseek.call(any(SPRING_PROMPT))).thenReturn(response("ok"));
        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", deepseek), adapter, auditor);

        OryxTool httpGet = new OryxTool() {
            @Override
            public String getName() {
                return "http_get";
            }

            @Override
            public String getDescription() {
                return "HTTP GET";
            }

            @Override
            public String getInputSchema() {
                return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                throw new UnsupportedOperationException();
            }
        };

        Prompt prompt = new Prompt("sys",
                List.of(Map.of("role", "user", "content", "hi")),
                List.of(httpGet));
        service.chat("s-1", profileUsing("deepseek"), prompt);

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(SPRING_PROMPT);
        verify(deepseek).call(captor.capture());

        ChatOptions options = captor.getValue().getOptions();
        assertFalse(ToolCallingChatOptions.isInternalToolExecutionEnabled(options),
                "Spring AI 自动工具执行必须被关闭");
        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) options;
        assertThat(toolOptions.getToolCallbacks()).hasSize(1);
        assertThat(toolOptions.getToolCallbacks().get(0).getToolDefinition().name()).isEqualTo("http_get");
    }

    @Test
    void 动态注册_provider可运行时加入() {
        DefaultProviderService service = new DefaultProviderService(Map.of(), adapter, auditor);
        service.register("kimi", mock(ChatModel.class));

        assertThat(service.hasProvider("kimi")).isTrue();
        assertThat(service.providerNames()).contains("kimi");
    }

    @Test
    void Profile缺少provider配置_抛出IllegalArgumentException() {
        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", mock(ChatModel.class)), adapter, auditor);
        Profile profile = new Profile();

        assertThatThrownBy(() -> service.chat("s-1", profile, Prompt.ofUserMessage("hi")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 可重试错误_每次尝试都落一行审计() {
        ChatModel deepseek = mock(ChatModel.class);
        when(deepseek.call(any(SPRING_PROMPT)))
                .thenThrow(new TransientAiException("503 Service Unavailable"))
                .thenReturn(response("ok"));

        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", deepseek), adapter, auditor, 2);

        LlmResponse result = service.chat("s-1", profileUsing("deepseek"), Prompt.ofUserMessage("hi"));

        assertThat(result.text()).isEqualTo("ok");
        // 真实请求 2 次 → 审计 2 行：一行失败、一行成功（账目与请求次数严格一致）
        verify(deepseek, times(2)).call(any(SPRING_PROMPT));
        verify(auditor).record(eq("s-1"), eq("deepseek"), eq("deepseek-model"),
                isNull(), eq(false), contains("503"), anyLong());
        verify(auditor).record(eq("s-1"), eq("deepseek"), eq("deepseek-model"),
                any(), eq(true), isNull(), anyLong());
    }

    @Test
    void 不可重试错误_只落一行审计且不再重试() {
        ChatModel deepseek = mock(ChatModel.class);
        when(deepseek.call(any(SPRING_PROMPT)))
                .thenThrow(new NonTransientAiException("400 Bad Request"));

        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", deepseek), adapter, auditor, 3);

        assertThatThrownBy(() -> service.chat("s-1", profileUsing("deepseek"), Prompt.ofUserMessage("hi")))
                .isInstanceOf(NonTransientAiException.class);

        // 参数错了再试一百次也不会对：只发一次请求、只记一行
        verify(deepseek).call(any(SPRING_PROMPT));
        verify(auditor).record(eq("s-1"), eq("deepseek"), eq("deepseek-model"),
                isNull(), eq(false), contains("400"), anyLong());
    }

    @Test
    void 重试耗尽_落满尝试次数并抛最后一次异常() {
        ChatModel deepseek = mock(ChatModel.class);
        when(deepseek.call(any(SPRING_PROMPT)))
                .thenThrow(new TransientAiException("500 Internal Server Error"));

        DefaultProviderService service = new DefaultProviderService(
                Map.of("deepseek", deepseek), adapter, auditor, 2);

        assertThatThrownBy(() -> service.chat("s-1", profileUsing("deepseek"), Prompt.ofUserMessage("hi")))
                .isInstanceOf(TransientAiException.class);

        verify(deepseek, times(2)).call(any(SPRING_PROMPT));
        verify(auditor, times(2)).record(eq("s-1"), eq("deepseek"), eq("deepseek-model"),
                isNull(), eq(false), contains("500"), anyLong());
    }
}
