package io.oryxos.provider.config;

/**
 * 全局层 Provider 配置非法时抛出（缺 name、重复 name、缺 API key 等）。
 */
public class ProviderConfigException extends RuntimeException {

    public ProviderConfigException(String message) {
        super(message);
    }
}
