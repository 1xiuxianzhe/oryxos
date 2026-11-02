package io.oryxos.tool.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * WebhookNotifyAdapter —— core 阶段唯一一档通知实现：通用 webhook 推送。
 *
 * <p>企业微信、飞书、钉钉的群机器人都提供 webhook 地址，一次实现即可覆盖大部分场景，
 * 不必去接每家的签名算法与 AccessToken 刷新——那些属扩展阶段。</p>
 *
 * <p>用 JDK {@code HttpClient} 而非 Spring {@code RestClient}：与既有 {@code HttpTool} 保持一致，
 * 且工具模块的类路径上不保证有 {@code spring-web}。</p>
 *
 * <p><b>请求体是通用格式，不是 IM 官方格式。</b>本类发的是带 content 字段的通用 JSON 对象；
 * 企业微信 / 钉钉 / 飞书官方群机器人要求的是各自的结构，直连官方机器人地址收不到消息。
 * 更麻烦的是这三家在业务出错时<b>仍返回 HTTP 200</b>，把错误码放在响应体里，
 * 因此当前"2xx 即成功"的判定会误报"已推送"。要接官方机器人需在机器人前挂一层格式转换，
 * 或新增厂商专用适配器（属扩展阶段）。详见 {@code specs/004-notify/quickstart.md} 第 5 节。</p>
 *
 * <p><b>装配方式</b>：本类靠 {@code @Component} 被组件扫描发现，
 * 而 {@link NotifyTargetResolver} 与 {@link io.oryxos.tool.builtin.NotifyTool} 是在
 * {@link io.oryxos.tool.config.NotifyAutoConfiguration} 里显式声明的 {@code @Bean}。
 * 保留 {@code @Component} 是为了与源文档示意一致；两种方式在本模块内等价，
 * 但改动任一处时需同步另一处，否则会出现重复 Bean。</p>
 */
@Component
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

    /** 渠道配置中承载目标地址的键。 */
    public static final String URL_KEY = "url";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebhookNotifyAdapter() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** 测试用：注入可控的客户端。 */
    WebhookNotifyAdapter(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public void send(NotifyTarget target, String content) {
        String url = target.configValue(URL_KEY);
        if (url == null || url.isBlank()) {
            throw new NotifyConfigException("渠道 " + target.channelType() + " 缺少 " + URL_KEY + " 配置");
        }

        String host = NotifyUrls.hostOf(url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody(content), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 错误信息只带 host，不带完整地址（地址里可能内嵌凭证）
            throw new NotifySendException(
                    "通知推送失败（网络异常 " + e.getClass().getSimpleName() + "，host=" + host + "）", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotifySendException("通知推送被中断（host=" + host + "）", true, e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // 5xx 可能是目标端临时故障，可重试；4xx 是请求本身有问题，重试无用
            throw new NotifySendException(
                    "通知推送失败（目标返回 HTTP " + status + "，host=" + host + "）", status >= 500);
        }
    }

    /** webhook 通用体：{"content": "..."}。 */
    private String jsonBody(String content) {
        try {
            return objectMapper.writeValueAsString(Map.of("content", content == null ? "" : content));
        } catch (Exception e) {
            throw new IllegalStateException("通知内容序列化失败", e);
        }
    }
}
