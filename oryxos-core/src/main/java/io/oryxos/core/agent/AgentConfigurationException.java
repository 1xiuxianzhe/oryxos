package io.oryxos.core.agent;

/**
 * 请求的 Agent 配置（Profile）不存在或不可用时抛出。
 *
 * <p>不允许返回空答案，必须让调用入口拿到可读的错误信息。</p>
 */
public class AgentConfigurationException extends RuntimeException {

    private final String profileName;

    public AgentConfigurationException(String profileName) {
        super("未找到 Profile: '" + profileName + "'，请检查 .oryxos/profiles 下的配置");
        this.profileName = profileName;
    }

    public String getProfileName() {
        return profileName;
    }
}
