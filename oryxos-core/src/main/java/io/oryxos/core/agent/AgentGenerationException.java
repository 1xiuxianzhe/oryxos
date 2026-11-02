package io.oryxos.core.agent;

/**
 * AgentGenerationException —— "按描述生成 Agent 文件"这条链路的失败。
 *
 * <p>刻意把两种性质完全不同的失败放在一个类型里，用 {@link #unavailable()} 区分：</p>
 * <ul>
 *   <li>{@code unavailable=true}：<b>能力没配</b>（缺 {@code oryxos.author.provider/model}、
 *       没有 {@code LlmClient}、模型侧不可用）→ HTTP 503，重试无意义，要改配置；</li>
 *   <li>{@code unavailable=false}：<b>模型吐出来的东西不能用</b>（没有 AGENT.md、frontmatter 非法、
 *       引用了未声明的 provider）→ HTTP 400，改描述或手工编辑即可。</li>
 * </ul>
 *
 * <p>分开的理由很实际：前端拿到 503 该提示"去配模型"，拿到 400 该提示"再改改描述"。
 * 混成一个 500 或一个 400，用户只会反复点重试。</p>
 */
public class AgentGenerationException extends RuntimeException {

    private final boolean unavailable;

    private AgentGenerationException(String message, boolean unavailable, Throwable cause) {
        super(message, cause);
        this.unavailable = unavailable;
    }

    /** 能力不可用（配置缺失 / 客户端缺失 / 模型侧故障）。 */
    public static AgentGenerationException unavailable(String message) {
        return new AgentGenerationException(message, true, null);
    }

    /** 生成结果不可用（调用方需求或模型输出问题）。 */
    public static AgentGenerationException invalid(String message) {
        return new AgentGenerationException(message, false, null);
    }

    /** 生成结果不合法，且附带底层原因（通常是解析异常）。 */
    public static AgentGenerationException invalid(String message, Throwable cause) {
        return new AgentGenerationException(message, false, cause);
    }

    public boolean unavailable() {
        return unavailable;
    }
}
