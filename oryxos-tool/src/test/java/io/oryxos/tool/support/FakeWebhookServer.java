package io.oryxos.tool.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地假 webhook，用于验证"推送协议"这一层。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer}，而不是 MockWebServer：
 * 同样是本地、离线、单测层，但**不引入任何新的第三方依赖**（MockWebServer 会连带 okhttp 与 kotlin）。</p>
 *
 * <p>绑定 127.0.0.1 的随机端口，多实例并存时互不干扰。</p>
 */
public final class FakeWebhookServer implements AutoCloseable {

    /** 收到的一次请求。 */
    public record ReceivedRequest(String method, String path, String contentType, String body) {
    }

    private static final String CONTEXT_PATH = "/hook";

    private final HttpServer server;
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger statusToReturn = new AtomicInteger(200);

    public FakeWebhookServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CONTEXT_PATH, this::handle);
        server.setExecutor(null);
        server.start();
    }

    /** 该假服务对应的 webhook 地址。 */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + CONTEXT_PATH;
    }

    /** 让后续请求返回指定状态码。 */
    public void respondWith(int status) {
        statusToReturn.set(status);
    }

    public List<ReceivedRequest> requests() {
        return List.copyOf(requests);
    }

    public ReceivedRequest lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        requests.add(new ReceivedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body));

        byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusToReturn.get(), response.length);
        try (var out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
