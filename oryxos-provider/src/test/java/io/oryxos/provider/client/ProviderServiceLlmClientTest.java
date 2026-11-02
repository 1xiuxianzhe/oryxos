package io.oryxos.provider.client;

import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.llm.LlmRequest;
import io.oryxos.core.profile.Profile;
import io.oryxos.provider.ProviderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 桥接契约测试：长期记忆顺序、工具只翻译不执行、异常透传、sessionId 透传。
 */
class ProviderServiceLlmClientTest {

    private Profile profile() {
        Profile profile = new Profile();
        profile.setName("ops-agent");
        Profile.ProviderConfig provider = new Profile.ProviderConfig();
        provider.setName("deepseek");
        provider.setModel("deepseek-flash");
        profile.setProvider(provider);
        return profile;
    }

    @Test
    void 长期记忆作为独立system消息_且位于会话历史之前() {
        ProviderService providerService = mock(ProviderService.class);
        when(providerService.chat(anyString(), any(), any()))
                .thenReturn(new io.oryxos.provider.LlmResponse("好的", List.of(), null));
        ProviderServiceLlmClient client = new ProviderServiceLlmClient(providerService);

        LlmRequest request = new LlmRequest("系统设定", "用户偏好：用中文",
                List.of(Map.of("role", "user", "content", "你好")), List.of());
        Profile profile = profile();

        client.call("s-1", profile, request);

        ArgumentCaptor<io.oryxos.provider.Prompt> captor =
                ArgumentCaptor.forClass(io.oryxos.provider.Prompt.class);
        verify(providerService).chat(eq("s-1"), eq(profile), captor.capture());

        List<Map<String, String>> history = captor.getValue().history();
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("role", "system").containsEntry("content", "用户偏好：用中文");
        assertThat(history.get(1)).containsEntry("role", "user");
        assertThat(captor.getValue().systemPrompt()).isEqualTo("系统设定");
    }

    @Test
    void 工具只翻译不执行() {
        ProviderService providerService = mock(ProviderService.class);
        when(providerService.chat(anyString(), any(), any()))
                .thenReturn(new io.oryxos.provider.LlmResponse("好的", List.of(), null));
        ProviderServiceLlmClient client = new ProviderServiceLlmClient(providerService);

        LlmRequest request = new LlmRequest("sys", "", List.of(),
                List.of(new LlmClient.ToolDescription("http_get", "HTTP GET", "{\"type\":\"object\"}")));

        client.call("s-1", profile(), request);

        ArgumentCaptor<io.oryxos.provider.Prompt> captor =
                ArgumentCaptor.forClass(io.oryxos.provider.Prompt.class);
        verify(providerService).chat(anyString(), any(), captor.capture());

        assertThat(captor.getValue().availableTools()).hasSize(1);
        assertThat(captor.getValue().availableTools().get(0).getName()).isEqualTo("http_get");
        assertThat(captor.getValue().availableTools().get(0).getInputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThatThrownBy(() -> captor.getValue().availableTools().get(0).execute(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 模型异常原样上抛_不吞不降级() {
        ProviderService providerService = mock(ProviderService.class);
        when(providerService.chat(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("connect timeout"));
        ProviderServiceLlmClient client = new ProviderServiceLlmClient(providerService);

        assertThatThrownBy(() -> client.call("s-1", profile(),
                new LlmRequest("sys", "", List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connect timeout");
    }

    @Test
    void 响应映射_text与toolCalls与usage一一对应() {
        ProviderService providerService = mock(ProviderService.class);
        when(providerService.chat(anyString(), any(), any()))
                .thenReturn(new io.oryxos.provider.LlmResponse("答复",
                        List.of(new io.oryxos.provider.LlmResponse.ToolCall("id-1", "http_get", "{\"url\":\"x\"}")),
                        new LlmClient.TokenUsage(1, 2, 3)));
        ProviderServiceLlmClient client = new ProviderServiceLlmClient(providerService);

        LlmClient.LlmResponse response = client.call("s-1", profile(),
                new LlmRequest("sys", "", List.of(), List.of()));

        assertThat(response.text()).isEqualTo("答复");
        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls().get(0).name()).isEqualTo("http_get");
        assertThat(response.toolCalls().get(0).arguments()).isEqualTo("{\"url\":\"x\"}");
        assertThat(response.tokenUsage().totalTokens()).isEqualTo(3);
    }
}
