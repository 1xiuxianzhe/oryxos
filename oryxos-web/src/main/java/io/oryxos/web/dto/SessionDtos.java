package io.oryxos.web.dto;

import java.util.List;

/**
 * 会话相关的传输模型。
 *
 * <p>统一用 record：字段名、类型、可空性编译期固定，springdoc 可据此生成 schema。
 * 时间一律用 ISO-8601 字符串，避免客户端对 {@code Instant} 的时区解释产生分歧。</p>
 */
public final class SessionDtos {

    private SessionDtos() {
    }

    /**
     * 创建会话请求。
     *
     * <p>幂等：同一 {@code (profile, userId)} 已有活跃会话时返回它，不新建。</p>
     *
     * @param profile Agent 名（可空，默认 {@code default}）
     * @param userId  用户标识（可空，默认 {@code anonymous}）
     */
    public record CreateSession(String profile, String userId) {
    }

    /**
     * 会话摘要。
     *
     * @param messageCount 消息条数（只给计数，不把历史整段搬进列表）
     */
    public record Summary(String sessionId,
                          String profileName,
                          String channel,
                          String userId,
                          String status,
                          int messageCount,
                          String createdAt,
                          String lastActiveAt) {
    }

    /**
     * 会话详情：摘要 + 最近若干条消息。
     *
     * @param messageLimit 本次返回的条数上限（显式声明，避免调用方以为拿到了全部历史）
     */
    public record Detail(Summary session, List<MessageView> messages, int messageLimit) {
    }

    /** 单条消息的只读视图。 */
    public record MessageView(String role, String content) {
    }

    /** 发消息请求。 */
    public record SendMessage(String content) {
    }

    /** 发消息响应。 */
    public record MessageResponse(String sessionId, String role, String content, String status) {
    }
}
