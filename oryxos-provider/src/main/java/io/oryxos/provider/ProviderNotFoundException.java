package io.oryxos.provider;

/**
 * Profile 引用的 provider 在显式映射表中不存在时抛出。
 * 不允许悄悄用错或留空跑过去，必须让上层拿到清晰的错误。
 */
public class ProviderNotFoundException extends RuntimeException {

    private final String providerName;

    public ProviderNotFoundException(String providerName) {
        super("未找到 Provider: '" + providerName + "'，请确认已在全局层 oryxos.providers 中声明该 provider");
        this.providerName = providerName;
    }

    public String getProviderName() {
        return providerName;
    }
}
