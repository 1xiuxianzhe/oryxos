package io.oryxos.core.agent;

/**
 * AgentNotFoundException —— 要操作的 Agent 在磁盘与注册表里都不存在。
 *
 * <p>刻意不复用 {@code ProfileNotFoundException}：那个类属于 Web 模块的错误语义
 * （"你请求的 Profile 不存在"），而这里是核心层的"目录也没有、索引也没有"。
 * core 不依赖 web，因此反向引用不成立；Web 层把两者都映射成 404 即可。</p>
 */
public class AgentNotFoundException extends RuntimeException {

    private final String name;

    public AgentNotFoundException(String name) {
        super("Agent 不存在: " + name);
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
