package io.oryxos.core.react;

import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.llm.LlmRequest;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.session.Session;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResolver;
import io.oryxos.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * ReActLoop —— 自实现 Reason + Act 循环（Agent 的大脑）。
 *
 * <p>主循环只做一件事：<b>调度</b>。拼 prompt、调模型、执行工具分别交给
 * {@link PromptBuilder}、{@link LlmClient}、{@link ToolExecutor}。</p>
 *
 * <p>一轮的固定动作：组装输入 → 调模型 → <b>先把响应累积进会话</b> →
 * <b>再看是否含工具调用</b>。含则顺序执行、回填结果，进入下一轮；不含则收尾。</p>
 *
 * <p>边界（设计时定死，不是写完再补）：</p>
 * <ul>
 *   <li><b>最大轮数</b>（默认 10）：防模型反复要调工具陷入死循环；</li>
 *   <li><b>整体耗时上限</b>（默认 60 秒）：防多轮累积把请求拖死，在步骤边界（每轮开始、工具执行后）检查；</li>
 *   <li><b>每轮都累积</b>：事后可审计，下一轮也接得上前一环。</li>
 * </ul>
 *
 * <p>出口有两条：{@link #runResult} 返回带过程元数据的 {@link ReActResult}（请求级审计需要它），
 * {@link #run} 是只取答复文本的兼容重载。</p>
 */
public class ReActLoop {

    private static final Logger logger = LoggerFactory.getLogger(ReActLoop.class);

    /** 模型既无文本也无工具请求时的收尾提示。 */
    public static final String EMPTY_RESPONSE_MESSAGE = "模型未返回有效内容，已停止本次处理";

    private final LlmClient llmClient;
    /**
     * 按 Agent 授权解析可用工具。
     *
     * <p>此处刻意从注册表内部构造，而不是把解析器提升为构造参数：循环只关心"这一轮能用哪些工具"，
     * 让调用方多传一个协作者并不会增加表达力，反而要改动所有既有构造点。</p>
     */
    private final ToolResolver toolResolver;
    private final PromptBuilder promptBuilder;
    private final ToolExecutor toolExecutor;
    private final LongSupplier clock;

    public ReActLoop(LlmClient llmClient,
                     ToolRegistry toolRegistry,
                     PromptBuilder promptBuilder,
                     ToolExecutor toolExecutor) {
        this(llmClient, toolRegistry, promptBuilder, toolExecutor, System::currentTimeMillis);
    }

    /** 测试用构造器：注入可控时钟，便于在不真正等待的情况下验证超时行为。 */
    ReActLoop(LlmClient llmClient,
              ToolRegistry toolRegistry,
              PromptBuilder promptBuilder,
              ToolExecutor toolExecutor,
              LongSupplier clock) {
        this.llmClient = llmClient;
        this.toolResolver = new ToolResolver(toolRegistry);
        this.promptBuilder = promptBuilder;
        this.toolExecutor = toolExecutor;
        this.clock = clock;
    }

    /**
     * 运行一轮 ReAct 循环，只取最终答复文本。
     *
     * @see #runResult(String, Session, String, Profile)
     */
    public String run(String sessionId, Session session, String userMessage, Profile profile) {
        return runResult(sessionId, session, userMessage, profile).text();
    }

    /**
     * 运行一轮 ReAct 循环，返回答复文本<b>与过程元数据</b>（停止原因、轮数、工具次数、token 汇总、耗时）。
     *
     * <p>元数据是请求级审计的输入：达轮数上限与空响应同样是"正常返回"，
     * 不把它们带出来，库里就无法区分"模型真答复"和"因边界停止"。</p>
     *
     * @param sessionId   会话 ID（审计关联必需）
     * @param session     当前会话（响应与工具结果都会累积进去）
     * @param userMessage 用户本次输入
     * @param profile     当前 Agent 配置（决定轮数上限与耗时上限）
     * @return 答复与过程元数据
     * @throws ProcessingTimeoutException 超过整体耗时上限（携带已完成的轮数/工具次数/token）
     * @throws ModelCallException         模型调用失败（原始异常在 cause 中）
     */
    public ReActResult runResult(String sessionId, Session session, String userMessage, Profile profile) {
        int maxIterations = profile.maxIterations();
        int timeoutSeconds = profile.processingTimeoutSeconds();
        long beganAt = clock.getAsLong();
        long deadline = beganAt + timeoutSeconds * 1000L;

        session.addMessage(new Session.Message("user", userMessage));
        logger.info("[ReAct] 处理开始: sessionId={}, profile={}, 轮数上限={}, 耗时上限={}秒",
                sessionId, profile.getName(), maxIterations, timeoutSeconds);

        int completedIterations = 0;
        int toolCallCount = 0;
        TokenUsage totalUsage = null;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            checkTimeout(deadline, timeoutSeconds, sessionId, completedIterations, toolCallCount, totalUsage);

            // 可用工具按当前 Agent 的授权声明解析，不再无条件暴露注册表全量
            List<OryxTool> tools = toolResolver.resolve(profile);
            LlmRequest request = promptBuilder.build(session, profile, tools);

            logger.debug("[ReAct] 第 {} 轮: sessionId={}, 可用工具={}", iteration, sessionId,
                    tools.stream().map(OryxTool::getName).collect(Collectors.toList()));

            LlmClient.LlmResponse response;
            try {
                response = llmClient.call(sessionId, profile, request);
            } catch (RuntimeException e) {
                logger.warn("[ReAct] 模型调用失败（{}）: sessionId={}, 第 {} 轮, error={}",
                        StopReason.MODEL_ERROR, sessionId, iteration, e.getMessage());
                throw new ModelCallException(e.getMessage(), e);
            }

            // 先累积，再判停止条件（顺序不可交换）
            appendAssistantResponse(session, response);
            completedIterations = iteration;
            totalUsage = accumulate(totalUsage, response.tokenUsage());

            if (!response.hasToolCalls()) {
                String text = response.text();
                if (text != null && !text.isBlank()) {
                    logger.info("[ReAct] 处理结束（{}）: sessionId={}, 轮数={}, 耗时={}ms",
                            StopReason.FINAL_ANSWER, sessionId, completedIterations,
                            clock.getAsLong() - beganAt);
                    return new ReActResult(text, StopReason.FINAL_ANSWER, completedIterations,
                            toolCallCount, totalUsage, clock.getAsLong() - beganAt);
                }
                logger.info("[ReAct] 处理结束（{}）: sessionId={}, 轮数={}",
                        StopReason.EMPTY_RESPONSE, sessionId, completedIterations);
                session.addMessage(new Session.Message("assistant", EMPTY_RESPONSE_MESSAGE));
                return new ReActResult(EMPTY_RESPONSE_MESSAGE, StopReason.EMPTY_RESPONSE,
                        completedIterations, toolCallCount, totalUsage, clock.getAsLong() - beganAt);
            }

            List<ToolResult> results = toolExecutor.executeAll(sessionId, response.toolCalls());
            toolCallCount += results.size();
            for (ToolResult result : results) {
                appendToolResult(session, result);
            }

            // 工具执行后补一个检查点：工具很慢时不必等到下一轮才中止
            checkTimeout(deadline, timeoutSeconds, sessionId, completedIterations, toolCallCount, totalUsage);
        }

        String finalText = "已达到最大轮数（" + maxIterations + "），已停止";
        session.addMessage(new Session.Message("assistant", finalText));
        logger.info("[ReAct] 处理结束（{}）: sessionId={}, 轮数={}, 耗时={}ms",
                StopReason.MAX_ITERATIONS, sessionId, completedIterations, clock.getAsLong() - beganAt);
        return new ReActResult(finalText, StopReason.MAX_ITERATIONS, completedIterations,
                toolCallCount, totalUsage, clock.getAsLong() - beganAt);
    }

    /**
     * 在步骤边界检查整体耗时，超时即中止。
     *
     * <p>携带已完成的进度，使请求级审计在失败场景下也能记下真实进展。</p>
     */
    private void checkTimeout(long deadline, int timeoutSeconds, String sessionId,
                              int completedIterations, int toolCallCount, TokenUsage totalUsage) {
        if (clock.getAsLong() >= deadline) {
            logger.warn("[ReAct] 处理结束（{}）: sessionId={}, 已执行轮数={}, 工具调用={}, 上限={}秒",
                    StopReason.TIMEOUT, sessionId, completedIterations, toolCallCount, timeoutSeconds);
            throw new ProcessingTimeoutException(timeoutSeconds, completedIterations, toolCallCount, totalUsage);
        }
    }

    /** 累加 token 用量；provider 未返回用量时按 0 处理，不做 null 传染。 */
    private static TokenUsage accumulate(TokenUsage base, TokenUsage delta) {
        if (base == null && delta == null) {
            return null;
        }
        TokenUsage left = base == null ? new TokenUsage(0, 0, 0) : base;
        TokenUsage right = delta == null ? new TokenUsage(0, 0, 0) : delta;
        return new TokenUsage(
                zeroIfNull(left.promptTokens()) + zeroIfNull(right.promptTokens()),
                zeroIfNull(left.completionTokens()) + zeroIfNull(right.completionTokens()),
                zeroIfNull(left.totalTokens()) + zeroIfNull(right.totalTokens()));
    }

    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    /** 把本轮模型响应累积进会话；工具请求也一并记录，保证事后可审计。 */
    private void appendAssistantResponse(Session session, LlmClient.LlmResponse response) {
        StringBuilder content = new StringBuilder();
        if (response.text() != null && !response.text().isBlank()) {
            content.append(response.text().trim());
        }
        if (response.hasToolCalls()) {
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append("[Tool Request] ").append(response.toolCalls().stream()
                    .map(call -> call.name() + "(" + (call.arguments() == null ? "" : call.arguments()) + ")")
                    .collect(Collectors.joining(", ")));
        }
        if (content.length() == 0) {
            content.append("[Empty Response]");
        }
        session.addMessage(new Session.Message("assistant", content.toString()));
    }

    /** 把工具结果（成功或失败）回填进会话，供下一轮模型看到。 */
    private void appendToolResult(Session session, ToolResult result) {
        String content = result.isSuccess()
                ? "[Tool Result]\n" + result.getContent()
                : "[Tool Error]\n" + result.getError();
        session.addMessage(new Session.Message("tool", content));
    }
}
