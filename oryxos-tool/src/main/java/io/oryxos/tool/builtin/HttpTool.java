package io.oryxos.tool.builtin;

import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxViolationException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * HttpTool —— 内置 HTTP Tool，发起 GET/POST 请求，执行前过一次沙箱。
 */
public class HttpTool implements OryxTool {

    private final String name;
    private final Sandbox sandbox;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public HttpTool(Sandbox sandbox) {
        this("http_get", sandbox);
    }

    public HttpTool(String name, Sandbox sandbox) {
        this.name = name;
        this.sandbox = sandbox;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return switch (name) {
            case "http_post" -> "发起 HTTP POST 请求并返回响应体。参数：url（目标地址）、body（请求体）、contentType（可选，默认 application/json）。";
            default -> "发起 HTTP GET 请求并返回响应体。参数：url（目标地址）。";
        };
    }

    @Override
    public String getInputSchema() {
        return switch (name) {
            case "http_post" -> """
                    {"type":"object","properties":{"url":{"type":"string"},"body":{"type":"string"},"contentType":{"type":"string"}},"required":["url","body"]}
                    """;
            default -> """
                    {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}
                    """;
        };
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String url = (String) input.get("url");
        if (url == null || url.isBlank()) {
            return ToolResult.fail("缺少 url 参数", false);
        }
        try {
            // 校验严格早于建立连接：拒绝时不会有任何请求发出
            sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            HttpRequest request = switch (name) {
                case "http_post" -> {
                    String body = (String) input.getOrDefault("body", "");
                    String contentType = (String) input.getOrDefault("contentType", "application/json");
                    yield builder
                            .header("Content-Type", contentType)
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                }
                default -> builder.GET().build();
            };

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                // 目标返回非 2xx 就是失败，不得改写成成功；5xx 可能是目标端临时故障，可重试
                return ToolResult.fail("HTTP 请求失败（目标返回 " + status + "）", status >= 500);
            }
            return ToolResult.ok(response.body());
        } catch (SandboxViolationException e) {
            // 越界属于配置问题：重试一万次也不会变成允许，必须标记为不可重试
            return ToolResult.fail(e.getMessage(), false);
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage(), true);
        }
    }
}
