package io.oryxos.web.dto;

/** Agent 无状态调用相关的传输模型。 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /**
     * 无状态调用请求。
     *
     * @param message 用户输入（必填，UTF-8 ≤ 32KB）
     * @param userId  调用方标识（可空，默认 {@code anonymous}），仅用于审计归类
     */
    public record InvokeRequest(String message, String userId) {
    }

    /** 无状态调用响应；{@code agent} 即被调用的 Profile 名。 */
    public record InvokeResponse(String sessionId, String agent, String content) {
    }
}
