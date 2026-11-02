package io.oryxos.tool.notify;

import io.oryxos.tool.support.FakeWebhookServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** US1 验收：把内容推送到配置好的 webhook（协议层，不依赖 Agent、Profile、Sandbox）。 */
class WebhookNotifyAdapterTest {

    private FakeWebhookServer server;
    private WebhookNotifyAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeWebhookServer();
        adapter = new WebhookNotifyAdapter();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private NotifyTarget targetOf(FakeWebhookServer target) {
        return new NotifyTarget(NotifyTargetResolver.WEBHOOK, Map.of("url", target.url()));
    }

    @Test
    void 推送到目标地址_请求体带content() {
        adapter.send(targetOf(server), "hello");

        FakeWebhookServer.ReceivedRequest request = server.lastRequest();
        assertThat(request).isNotNull();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.contentType()).contains("application/json");
        assertThat(request.body()).contains("\"content\"").contains("hello");
    }

    @Test
    void 地址取自目标配置而非硬编码() throws IOException {
        try (FakeWebhookServer another = new FakeWebhookServer()) {
            adapter.send(targetOf(another), "hi");

            assertThat(another.requests()).hasSize(1);
            assertThat(server.requests()).as("不能打到别的地址").isEmpty();
        }
    }

    @Test
    void 中文内容按UTF8传输() {
        adapter.send(targetOf(server), "测试消息");

        assertThat(server.lastRequest().body()).contains("测试消息");
    }

    @Test
    void 目标返回5xx_抛出可重试异常且信息不含完整地址() {
        server.respondWith(500);
        String url = server.url();

        Throwable thrown = catchThrowable(() -> adapter.send(
                new NotifyTarget(NotifyTargetResolver.WEBHOOK, Map.of("url", url)), "x"));

        assertThat(thrown).isInstanceOf(NotifySendException.class);
        assertThat(((NotifySendException) thrown).isRetryable()).isTrue();
        assertThat(thrown.getMessage())
                .as("只允许出现 host，不得出现完整地址")
                .doesNotContain(url)
                .contains(NotifyUrls.hostOf(url));
    }

    @Test
    void 目标返回4xx_不可重试() {
        server.respondWith(400);

        Throwable thrown = catchThrowable(() -> adapter.send(targetOf(server), "x"));

        assertThat(thrown).isInstanceOf(NotifySendException.class);
        assertThat(((NotifySendException) thrown).isRetryable()).isFalse();
    }

    @Test
    void 缺少url配置_抛出配置异常且不发请求() {
        Throwable thrown = catchThrowable(() -> adapter.send(
                new NotifyTarget(NotifyTargetResolver.WEBHOOK, Map.of()), "x"));

        assertThat(thrown).isInstanceOf(NotifyConfigException.class);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void 网络不可达_抛出可重试异常且信息不含完整地址() {
        String url = "http://127.0.0.1:1/hook";

        Throwable thrown = catchThrowable(() -> adapter.send(
                new NotifyTarget(NotifyTargetResolver.WEBHOOK, Map.of("url", url)), "x"));

        assertThat(thrown).isInstanceOf(NotifySendException.class);
        assertThat(((NotifySendException) thrown).isRetryable()).isTrue();
        assertThat(thrown.getMessage()).doesNotContain(url);
    }
}
