package io.oryxos.provider;

import io.oryxos.core.audit.LlmCallAuditor;
import io.oryxos.core.audit.NoopLlmCallAuditor;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.profile.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DefaultProviderService —— ProviderService 的默认实现。
 *
 * <p>三条关键约束：</p>
 * <ol>
 *   <li><b>显式映射</b>：维护 {@code provider name → ChatModel} 的 Map，不靠类型扫描；取不到直接抛
 *       {@link ProviderNotFoundException}。</li>
 *   <li><b>关闭自动执行</b>：请求里把 {@code internalToolExecutionEnabled} 置为 {@code false}，
 *       工具执行权攥在 ReActLoop + ToolExecutor 手里（否则工具会被调两次）。</li>
 *   <li><b>审计留痕</b>：<b>每一次尝试</b>都落一条 {@code llm_calls}（成功或失败各一行，失败带
 *       error_message）。重试是这一层显式做的，不用 Spring AI 自带的重试模板——否则中间失败
 *       会被框架吞掉，账目上的行数就小于真实请求次数，"某次对话到底发了几次请求"再也查不清。</li>
 * </ol>
 */
public class DefaultProviderService implements ProviderService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProviderService.class);

    /** 默认最大尝试次数：1 次正常 + 2 次重试。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 退避基数：第 n 次失败后等 {@code 500 * n} 毫秒。 */
    private static final long BASE_BACKOFF_MILLIS = 500;

    private final Map<String, ChatModel> providerMap = new ConcurrentHashMap<>();
    private final ToolSchemaAdapter toolSchemaAdapter;
    private final LlmCallAuditor auditor;
    private final int maxAttempts;

    public DefaultProviderService(Map<String, ChatModel> providers,
                                  ToolSchemaAdapter toolSchemaAdapter,
                                  LlmCallAuditor auditor) {
        this(providers, toolSchemaAdapter, auditor, DEFAULT_MAX_ATTEMPTS);
    }

    public DefaultProviderService(Map<String, ChatModel> providers,
                                  ToolSchemaAdapter toolSchemaAdapter,
                                  LlmCallAuditor auditor,
                                  int maxAttempts) {
        if (providers != null) {
            providerMap.putAll(providers);
        }
        this.toolSchemaAdapter = toolSchemaAdapter == null ? new ToolSchemaAdapter() : toolSchemaAdapter;
        this.auditor = auditor == null ? NoopLlmCallAuditor.INSTANCE : auditor;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** 显式注册一个 provider（运行时动态注册）。 */
    public void register(String providerName, ChatModel model) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("providerName 不能为空");
        }
        if (model == null) {
            throw new IllegalArgumentException("model 不能为 null");
        }
        providerMap.put(providerName, model);
        logger.info("已注册 Provider: {}", providerName);
    }

    /** 当前已注册的 provider 名快照。 */
    public Set<String> providerNames() {
        return Set.copyOf(providerMap.keySet());
    }

    public boolean hasProvider(String providerName) {
        return providerName != null && providerMap.containsKey(providerName);
    }

    @Override
    public LlmResponse chat(String sessionId, Profile profile, Prompt prompt) {
        if (profile == null || profile.getProvider() == null) {
            throw new IllegalArgumentException("Profile 缺少 provider 配置，无法路由到具体模型");
        }
        String providerName = profile.getProvider().getName();
        String modelName = profile.getProvider().getModel();

        ChatModel model = providerName == null ? null : providerMap.get(providerName);
        if (model == null) {
            logger.warn("未找到 Provider: '{}'，当前已注册: {}", providerName, providerMap.keySet());
            throw new ProviderNotFoundException(providerName);
        }

        List<ToolCallback> toolCallbacks = toolSchemaAdapter.toSpringAiTools(prompt.availableTools());

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 每次尝试重建请求：失败可能把 messages / options 弄脏，复用会让下一次带着残状态
            org.springframework.ai.chat.prompt.Prompt request = buildRequest(prompt, profile, toolCallbacks);
            long startedAt = System.currentTimeMillis();
            try {
                ChatResponse response = model.call(request);
                LlmResponse llmResponse = toLlmResponse(response);
                long latencyMs = System.currentTimeMillis() - startedAt;
                logger.debug("LLM 调用成功: sessionId={}, provider={}, model={}, 尝试={}/{}, 耗时={}ms, tokens={}, toolCalls={}",
                        sessionId, providerName, modelName, attempt, maxAttempts, latencyMs,
                        llmResponse.usage(), llmResponse.toolCalls().size());
                auditor.record(sessionId, providerName, modelName, llmResponse.usage(), true, null, latencyMs);
                return llmResponse;
            } catch (RuntimeException e) {
                long latencyMs = System.currentTimeMillis() - startedAt;
                lastError = e;
                // 每一次尝试都落一行：审计账目 = 真实请求次数（含最终失败的那次）
                auditor.record(sessionId, providerName, modelName, null, false, e.getMessage(), latencyMs);

                boolean canRetry = retryable(e);
                if (!canRetry || attempt == maxAttempts) {
                    logger.warn("LLM 调用失败（第 {}/{} 次，{}）: sessionId={}, provider={}, model={}, 耗时={}ms, error={}",
                            attempt, maxAttempts, canRetry ? "已达尝试上限" : "不可重试",
                            sessionId, providerName, modelName, latencyMs, e.getMessage());
                    break;
                }

                long backoffMillis = BASE_BACKOFF_MILLIS * attempt;
                logger.warn("LLM 调用失败（第 {}/{} 次），{}ms 后重试: sessionId={}, provider={}, model={}, error={}",
                        attempt, maxAttempts, backoffMillis, sessionId, providerName, modelName, e.getMessage());
                if (!sleep(backoffMillis)) {
                    // 线程被中断：不再重试，把中断留给上层判断
                    break;
                }
            }
        }

        throw lastError;
    }

    /**
     * 是否值得再试一次。
     *
     * <p>三类分法，依据是"再试一次有没有可能变好"：</p>
     * <ul>
     *   <li><b>不重试</b>：4xx（{@link NonTransientAiException}，参数/密钥错了，重试只是重复错误）；</li>
     *   <li><b>不重试</b>：读超时（模型不响应，重试只会把延迟翻倍，还可能吃掉整体预算）；</li>
     *   <li><b>重试</b>：5xx / 429（{@link TransientAiException}）与连接抖动（{@link ResourceAccessException}）。</li>
     * </ul>
     */
    static boolean retryable(RuntimeException error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = error; current != null; current = current.getCause()) {
            chain.add(current);
            if (current.getCause() == current) {
                break;
            }
        }
        if (chain.stream().anyMatch(t -> t instanceof NonTransientAiException)) {
            return false;
        }
        if (chain.stream().anyMatch(t -> t instanceof java.net.SocketTimeoutException)) {
            return false;
        }
        return chain.stream().anyMatch(t -> t instanceof TransientAiException
                || t instanceof ResourceAccessException);
    }

    /** 退避等待；被中断时返回 false（调用方据此停止重试）。 */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 构造 Spring AI 请求。package-private 便于测试断言「自动执行已关闭」与「schema 已带上」。
     */
    org.springframework.ai.chat.prompt.Prompt buildRequest(Prompt prompt,
                                                           Profile profile,
                                                           List<ToolCallback> toolCallbacks) {
        List<Message> messages = new ArrayList<>();
        if (prompt.systemPrompt() != null && !prompt.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(prompt.systemPrompt()));
        }
        for (Map<String, String> msg : prompt.history()) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            messages.add(switch (role) {
                case "system" -> new SystemMessage(content);
                case "assistant" -> new AssistantMessage(content);
                default -> new UserMessage(content);
            });
        }

        Profile.ProviderConfig provider = profile.getProvider();
        ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder()
                // 关键：关闭 Spring AI 的自动工具执行
                .internalToolExecutionEnabled(false);
        if (provider.getModel() != null && !provider.getModel().isBlank()) {
            optionsBuilder.model(provider.getModel());
        }
        if (provider.getTemperature() != null) {
            optionsBuilder.temperature(provider.getTemperature());
        }
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            optionsBuilder.toolCallbacks(toolCallbacks);
        }

        return new org.springframework.ai.chat.prompt.Prompt(messages, optionsBuilder.build());
    }

    private LlmResponse toLlmResponse(ChatResponse response) {
        if (response == null) {
            return new LlmResponse(null, List.of(), null);
        }

        String text = null;
        List<LlmResponse.ToolCall> toolCalls = new ArrayList<>();
        Generation generation = response.getResult();
        if (generation != null && generation.getOutput() != null) {
            AssistantMessage output = generation.getOutput();
            text = output.getText();
            if (output.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
                    toolCalls.add(new LlmResponse.ToolCall(tc.id(), tc.name(), tc.arguments()));
                }
            }
        }

        TokenUsage usage = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage u = response.getMetadata().getUsage();
            usage = new TokenUsage(u.getPromptTokens(), u.getCompletionTokens(), u.getTotalTokens());
        }
        return new LlmResponse(text, toolCalls, usage);
    }
}
