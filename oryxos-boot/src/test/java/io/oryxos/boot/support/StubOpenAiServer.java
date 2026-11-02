package io.oryxos.boot.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假的 OpenAI 兼容端点：让全链路集成测试在**无 API key、无外网**的条件下跑完真链路。
 *
 * <p>为什么不用真实模型：真模型测试要密钥、要外网、结果不稳定，只能"发版前手动跑"；
 * 而全链路对账（llm_calls / tool_invocations / agent_runs 三张表是否自洽）恰恰最该每次构建都跑。
 * 把"模型"换成可控的 HTTP 桩之后，链路上其它每一站——Spring AI 适配、Provider 审计、
 * ReAct 循环、ToolExecutor、沙箱、Session 持久化、SQLite——全都还是真的。</p>
 *
 * <p>行为：奇数轮请求返回 {@code tool_calls}（可多条），偶数轮返回最终答复——
 * 正好对应「模型要先工具 → 拿到结果再作答」的两轮节奏。每次对话前调 {@link #reset()}
 * 让计数归零，多个测试方法互不影响。</p>
 */
public final class StubOpenAiServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一条工具调用：工具名 + 参数 JSON。 */
    public record ToolCall(String name, String arguments) {
    }

    private final HttpServer server;
    private final List<ToolCall> toolCalls;
    private final String finalAnswer;
    private final AtomicInteger chatRequests = new AtomicInteger();
    private final AtomicBoolean toolCallServed = new AtomicBoolean();
    private volatile int failStatus;

    private StubOpenAiServer(List<ToolCall> toolCalls, String finalAnswer) throws IOException {
        this.toolCalls = toolCalls;
        this.finalAnswer = finalAnswer;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public static StubOpenAiServer start(List<ToolCall> toolCalls, String finalAnswer) {
        try {
            return new StubOpenAiServer(toolCalls, finalAnswer);
        } catch (IOException e) {
            throw new IllegalStateException("启动桩模型失败", e);
        }
    }

    /** 基址，配到 {@code oryxos.providers[0].base-url}。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 收到的 chat 请求次数（= 本次对话的模型调用轮数）。 */
    public int chatRequests() {
        return chatRequests.get();
    }

    /** 归零状态，让下一次请求重新从"要工具"开始（一次对话 = 一次 reset）。 */
    public void reset() {
        chatRequests.set(0);
        toolCallServed.set(false);
        failStatus = 0;
    }

    /**
     * 让下一次 chat 请求返回 400（不可重试）。
     *
     * <p>用来验证"单轮失败不拖垮链路"：400 不会被自动重试，能稳定走到
     * {@code DefaultProviderService} 的失败分支。</p>
     */
    public void failNextChat() {
        this.failStatus = 400;
    }

    /**
     * 让下一次 chat 请求返回 500（可重试）。
     *
     * <p>用来验证"重试是显式做的、每次尝试都留痕"：若 Spring AI 自带的重试还开着，
     * 一次 500 会在框架内部被重发多次，{@code chatRequests()} 会明显大于预期。</p>
     */
    public void failNextChatWithServerError() {
        this.failStatus = 500;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------- HTTP ----------

    private void handle(HttpExchange exchange) {
        try {
            // 请求体读掉即可：判定不依赖其内容（见 toolCallServed 的说明）
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            if (!path.endsWith("/chat/completions")) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            chatRequests.incrementAndGet();

            if (failStatus != 0) {
                int status = failStatus;
                failStatus = 0;
                respond(exchange, status,
                        "{\"error\":{\"message\":\"stub failure " + status + "\",\"type\":\"stub_error\"}}");
                return;
            }

            // 一条对话只发一次 tool_calls：失败的那次不会走到这里（上面的分支已返回），
            // 所以重试仍会拿到 tool_calls，节奏与"不重试"完全一致。
            boolean firstResponseOfTurn = !toolCallServed.getAndSet(true);
            respond(exchange, 200, firstResponseOfTurn ? toolCallBody() : finalAnswerBody());
        } catch (IOException e) {
            // 桩服务自身的 IO 异常没有更好的处理方式，直接放弃这次响应
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------- 响应体 ----------

    private String toolCallBody() {
        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall call = toolCalls.get(i);
            if (i > 0) {
                calls.append(',');
            }
            calls.append("""
                    {"id":"call_%d","type":"function","function":{"name":%s,"arguments":%s}}"""
                    .formatted(i + 1, quote(call.name()), quote(call.arguments())));
        }
        return """
                {"id":"chatcmpl-stub","object":"chat.completion","created":1700000000,"model":"stub-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[%s]},
                 "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}
                """.formatted(calls);
    }

    private String finalAnswerBody() {
        return """
                {"id":"chatcmpl-stub-2","object":"chat.completion","created":1700000001,"model":"stub-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":150,"completion_tokens":30,"total_tokens":180}}
                """.formatted(quote(finalAnswer));
    }

    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
