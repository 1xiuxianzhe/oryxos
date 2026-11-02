package io.oryxos.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局层（application.yaml）Provider 配置。
 *
 * <pre>
 * oryxos:
 *   providers:
 *     - name: deepseek
 *       base-url: https://api.deepseek.com
 *       api-key: ${DEEPSEEK_API_KEY}
 * </pre>
 *
 * <p>全局层只解决「连不连得上」：声明这个实例接了哪些 provider、凭证从哪个环境变量读。
 * 具体某个 Agent 用哪个 provider、哪个 model、什么温度，由 Profile 层决定。</p>
 */
@ConfigurationProperties(prefix = "oryxos")
public class OryxosProviderProperties {

    /** OryxOS 工作区目录（默认 ${user.dir}/.oryxos）。 */
    private String workspace;

    private List<Provider> providers = new ArrayList<>();

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }

    /** 单个 provider 的连接信息。 */
    public static class Provider {

        /** provider 名（deepseek / kimi / qwen ...），全局唯一。 */
        private String name;

        /** API key，建议用 ${ENV_VAR} 从环境变量读取。 */
        private String apiKey;

        /** API base URL（OpenAI 兼容端点）。 */
        private String baseUrl;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
