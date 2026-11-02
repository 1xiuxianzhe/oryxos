package io.oryxos.provider.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 全局层 Provider 配置校验工具类。
 *
 * <p>规则：provider.name 非空、全局唯一；api-key 非空（且应来自环境变量）。
 * 校验失败直接抛 {@link ProviderConfigException}，让问题在启动阶段就暴露。</p>
 *
 * <p>无状态纯函数，以静态方法提供，调用方无需实例化。</p>
 */
public final class ProviderConfigValidator {

    /** 未被解析的 ${...} 占位符（Spring 绑定对解析失败的值会原样保留）。 */
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{.+?}");

    private ProviderConfigValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 校验全局层 providers 列表。
     *
     * @return 校验通过的 provider 名集合（供 Profile 引用校验使用）
     */
    public static Set<String> validate(OryxosProviderProperties properties) {
        List<OryxosProviderProperties.Provider> providers =
                properties == null ? List.of() : properties.getProviders();

        Set<String> names = new HashSet<>();
        for (OryxosProviderProperties.Provider provider : providers) {
            String name = provider.getName();
            if (name == null || name.isBlank()) {
                throw new ProviderConfigException("oryxos.providers 中存在 name 为空的条目");
            }
            if (!names.add(name)) {
                throw new ProviderConfigException("oryxos.providers 中 provider 名重复: '" + name + "'");
            }
            String apiKey = provider.getApiKey();
            String envVarName = name.toUpperCase().replace('-', '_') + "_API_KEY";
            if (apiKey == null || apiKey.isBlank()) {
                throw new ProviderConfigException(
                        "provider '" + name + "' 缺少 api-key，请设置环境变量 " + envVarName
                                + "（配置中用 ${" + envVarName + "} 占位）");
            }
            // Spring 绑定对无法解析的占位符会原样保留，必须在这里拦住，
            // 否则字面量 "${DEEPSEEK_API_KEY}" 会被当成 key 发给模型，只会收到一个 401。
            if (UNRESOLVED_PLACEHOLDER.matcher(apiKey).find()) {
                throw new ProviderConfigException(
                        "provider '" + name + "' 的 api-key 未解析，当前值仍是占位符 " + apiKey
                                + "，请先设置环境变量 " + envVarName);
            }
        }
        return names;
    }
}
