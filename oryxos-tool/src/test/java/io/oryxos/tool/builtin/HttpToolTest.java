package io.oryxos.tool.builtin;

import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import io.oryxos.tool.support.FakeWebhookServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 类内置工具：白名单内可请求、越界被拦且**没有请求发出**、非 2xx 是失败而非成功。
 *
 * <p>见 {@code specs/005-tool-system/contracts/tool-invocation.md} 第 6 节验证项 1、2、6、7。</p>
 */
class HttpToolTest {

    private static HttpTool tool(String name, String... allowedDomains) {
        return new HttpTool(name, new WhitelistSandbox(List.of(), List.of(), List.of(allowedDomains)));
    }

    // ---------- 放行 ----------

    @Test
    void http_get放行并返回响应体() throws IOException {
        try (FakeWebhookServer server = new FakeWebhookServer()) {
            ToolResult result = tool("http_get", "127.0.0.1").execute(Map.of("url", server.url()));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).isEqualTo("ok");
            assertThat(server.lastRequest().method()).isEqualTo("GET");
        }
    }

    @Test
    void http_post把请求体发出去() throws IOException {
        try (FakeWebhookServer server = new FakeWebhookServer()) {
            ToolResult result = tool("http_post", "127.0.0.1")
                    .execute(Map.of("url", server.url(), "body", "{\"hello\":\"world\"}"));

            assertThat(result.isSuccess()).isTrue();
            assertThat(server.lastRequest().method()).isEqualTo("POST");
            assertThat(server.lastRequest().body()).contains("hello");
        }
    }

    // ---------- 越界：被拦 + 零请求 ----------

    @Test
    void 白名单外域名被拒_且没有请求发出() throws IOException {
        try (FakeWebhookServer server = new FakeWebhookServer()) {
            // 白名单里没有 127.0.0.1，而假服务恰好是它
            ToolResult result = tool("http_get", "api.example.com").execute(Map.of("url", server.url()));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("白名单");
            assertThat(result.isRetryable()).isFalse();
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void 域名清单为空时一律拒绝() throws IOException {
        try (FakeWebhookServer server = new FakeWebhookServer()) {
            ToolResult result = tool("http_get").execute(Map.of("url", server.url()));

            assertThat(result.isSuccess()).isFalse();
            assertThat(server.requests()).isEmpty();
        }
    }

    // ---------- 非 2xx ----------

    @Test
    void 目标返回5xx时是失败且可重试() throws IOException {
        try (FakeWebhookServer server = new FakeWebhookServer()) {
            server.respondWith(500);

            ToolResult result = tool("http_get", "127.0.0.1").execute(Map.of("url", server.url()));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("500");
            assertThat(result.isRetryable()).isTrue();
        }
    }

    @Test
    void 目标返回4xx时是失败且不可重试() throws IOException {
        try (FakeWebhookServer server = new FakeWebhookServer()) {
            server.respondWith(404);

            ToolResult result = tool("http_get", "127.0.0.1").execute(Map.of("url", server.url()));

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("404");
            assertThat(result.isRetryable()).isFalse();
        }
    }

    @Test
    void 缺少url参数时失败且不可重试() {
        ToolResult result = tool("http_get", "127.0.0.1").execute(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("url");
    }
}
