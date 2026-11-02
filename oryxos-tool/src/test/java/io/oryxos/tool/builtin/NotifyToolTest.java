package io.oryxos.tool.builtin;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.NotifyTarget;
import io.oryxos.tool.notify.NotifyTargetResolver;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** US2 / US3 验收：Agent 通过 notify 主动推送，且校验永远先于发送。 */
class NotifyToolTest {

    private static final String WEBHOOK_URL = "https://qyapi.example.com/hook?key=SECRET123";

    private final NotifyTargetResolver resolver = new NotifyTargetResolver();
    private final NotifyChannelAdapter adapter = mock(NotifyChannelAdapter.class);
    private final Sandbox sandbox = mock(Sandbox.class);

    @AfterEach
    void clearContext() {
        AgentContext.clear();
    }

    private static Profile profileWithChannels(List<Profile.NotifyChannelConfig> channels) {
        Profile profile = new Profile();
        profile.setName("demo");
        profile.setNotifyChannels(channels);
        return profile;
    }

    private static Profile.NotifyChannelConfig webhookChannel(String url) {
        Profile.NotifyChannelConfig channel = new Profile.NotifyChannelConfig();
        channel.setType("webhook");
        channel.setConfig(Map.of("url", url));
        return channel;
    }

    private void givenCurrentProfile(Profile profile) {
        AgentContext.set(profile);
    }

    // ---------- 契约：名称 / 描述 / 入参 schema ----------

    @Test
    void 工具名与入参schema符合契约() {
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);

        assertThat(tool.getName()).isEqualTo("notify");
        assertThat(tool.getDescription()).contains("推送");
        assertThat(tool.getInputSchema())
                .contains("\"content\"")
                .contains("\"channel\"")
                .contains("\"required\":[\"content\"]");
    }

    // ---------- US2：渠道解析与失败语义 ----------

    @Test
    void 未配置渠道时返回失败而非假成功() {
        givenCurrentProfile(profileWithChannels(null));
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);

        ToolResult result = tool.execute(Map.of("content", "hello"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("未配置通知渠道");
        assertThat(result.isRetryable()).isFalse();
        verifyNoInteractions(adapter);
    }

    @Test
    void 内容为空时拒绝且不发送() {
        givenCurrentProfile(profileWithChannels(List.of(webhookChannel(WEBHOOK_URL))));
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);

        ToolResult result = tool.execute(Map.of("content", "   "));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("不能为空");
        verifyNoInteractions(adapter);
    }

    @Test
    void 成功推送时把解析出的目标与内容交给适配器() {
        givenCurrentProfile(profileWithChannels(List.of(webhookChannel(WEBHOOK_URL))));
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);

        ToolResult result = tool.execute(Map.of("content", "测试消息"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("已推送");
        verify(adapter).send(
                eq(new NotifyTarget("webhook", Map.of("url", WEBHOOK_URL))), eq("测试消息"));
    }

    @Test
    void 显式指定的渠道不存在时返回失败() {
        givenCurrentProfile(profileWithChannels(List.of(webhookChannel(WEBHOOK_URL))));
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);

        ToolResult result = tool.execute(Map.of("content", "hi", "channel", "feishu"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("渠道 feishu 未配置");
        verifyNoInteractions(adapter);
    }

    @Test
    void 发送失败时按可重试标记返回失败() {
        givenCurrentProfile(profileWithChannels(List.of(webhookChannel(WEBHOOK_URL))));
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);
        doThrow(new io.oryxos.tool.notify.NotifySendException("通知推送失败（目标返回 HTTP 500，host=host）", true))
                .when(adapter).send(any(), anyString());

        ToolResult result = tool.execute(Map.of("content", "hi"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getError()).doesNotContain(WEBHOOK_URL);
    }

    // ---------- US3：校验先于发送 ----------

    @Test
    void 发送前必须先过白名单校验() {
        givenCurrentProfile(profileWithChannels(List.of(webhookChannel(WEBHOOK_URL))));
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);

        tool.execute(Map.of("content", "hello"));

        var inOrder = inOrder(sandbox, adapter);
        inOrder.verify(sandbox).enforce(new SandboxAction(ActionType.HTTP_REQUEST, WEBHOOK_URL));
        inOrder.verify(adapter).send(any(), eq("hello"));
    }

    @Test
    void 校验不通过时一次都不发送() {
        givenCurrentProfile(profileWithChannels(List.of(webhookChannel(WEBHOOK_URL))));
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);
        doThrow(new SandboxViolationException("HTTP 域名不在白名单内: qyapi.example.com"))
                .when(sandbox).enforce(any(SandboxAction.class));

        ToolResult result = tool.execute(Map.of("content", "hello"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("安全校验拦截");
        assertThat(result.isRetryable()).isFalse();
        verify(adapter, never()).send(any(), anyString());
    }

    @Test
    void 没有沙箱时拒绝构造工具() {
        // 007 起沙箱是强制依赖："未装配就放行"的状态被取消，只能在构造期失败，不能带病运行
        assertThatThrownBy(() -> new NotifyTool(resolver, adapter, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Sandbox");
    }

    @Test
    void 没有处理上下文时返回失败() {
        NotifyTool tool = new NotifyTool(resolver, adapter, sandbox);

        ToolResult result = tool.execute(Map.of("content", "hello"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("没有处理上下文");
        verifyNoInteractions(adapter);
    }
}
