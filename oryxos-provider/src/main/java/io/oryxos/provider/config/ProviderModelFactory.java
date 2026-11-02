package io.oryxos.provider.config;

import io.oryxos.provider.ProviderModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根据全局层配置，按 provider 列表逐条创建对应的 {@link ChatModel}，
 * 建立 provider name → ChatModel 的显式映射。
 *
 * <p>各家（DeepSeek / Kimi / Qwen 等）均提供 OpenAI 兼容端点，因此统一用 OpenAI connector +
 * 各自 base-url 接入，避免为每家引入独立 starter（部分里程碑版本 BOM 未必包含）。</p>
 *
 * <p>无状态纯函数，以静态方法提供，调用方无需实例化。</p>
 */
public final class ProviderModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(ProviderModelFactory.class);

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** 连接超时：DNS / TCP 卡住不该无限等（默认 HTTP 客户端没有读超时，等于可以永远挂着）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 读超时：单次模型调用的硬边界。
     *
     * <p>刻意小于 Agent 整体 60 秒：超时要发生在"这一轮"内部，循环才有机会按失败收尾；
     * 若两轮对话里第一轮就吃掉全部预算，最终只能报一个笼统的 AGENT_TIMEOUT，
     * 连"哪一轮挂的"都看不出来。</p>
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    private ProviderModelFactory() {
        // 工具类，禁止实例化
    }

    public static ProviderModelRegistry build(OryxosProviderProperties properties) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        for (OryxosProviderProperties.Provider provider : properties.getProviders()) {
            models.put(provider.getName(), createChatModel(provider));
        }
        return new ProviderModelRegistry(models);
    }

    /** 为单个 provider 创建 ChatModel；失败时抛出带上下文的配置异常。 */
    private static ChatModel createChatModel(OryxosProviderProperties.Provider provider) {
        String baseUrl = provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()
                ? DEFAULT_BASE_URL
                : provider.getBaseUrl();
        // 只打印掩码后的 key，避免密钥进入日志
        logger.info("  - 创建 ChatModel: provider={}, base-url={}, api-key={}",
                provider.getName(), baseUrl, mask(provider.getApiKey()));
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
            requestFactory.setReadTimeout(READ_TIMEOUT);

            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(provider.getApiKey())
                    .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                    .build();
            return OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    // 关闭 Spring AI 自带的重试：它会把中间失败吞在里面，只在最终结果里体现，
                    // 于是 llm_calls 的行数与真实请求次数对不上（5xx 重试 3 次只记 1 行）。
                    // 重试统一由 DefaultProviderService 显式做，每次尝试各落一行审计。
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                    .build();
        } catch (RuntimeException e) {
            throw new ProviderConfigException(
                    "创建 provider '" + provider.getName() + "' 的 ChatModel 失败: " + e.getMessage());
        }
    }

    /** 掩码 API key：保留首尾少量字符，中间用 **** 代替。 */
    static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "<empty>";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
