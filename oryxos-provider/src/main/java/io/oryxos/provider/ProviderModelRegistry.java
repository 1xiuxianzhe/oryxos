package io.oryxos.provider;

import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

/**
 * Provider 名到 ChatModel 的显式映射载体。
 *
 * <p>之所以单独包一层而不直接暴露 {@code Map<String, ChatModel>} Bean，是为了避免 Spring
 * 把「Map 类型 Bean」按 bean name 聚合注入的歧义——这里的 key 必须是配置里写死的 provider name。</p>
 */
public class ProviderModelRegistry {

    private final Map<String, ChatModel> models;

    public ProviderModelRegistry(Map<String, ChatModel> models) {
        this.models = models == null ? Map.of() : Map.copyOf(models);
    }

    public ChatModel get(String providerName) {
        return models.get(providerName);
    }

    public boolean contains(String providerName) {
        return providerName != null && models.containsKey(providerName);
    }

    public Map<String, ChatModel> asMap() {
        return models;
    }

    public int size() {
        return models.size();
    }
}
