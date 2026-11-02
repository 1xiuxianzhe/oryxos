package io.oryxos.web.dto;

/**
 * 对话请求体。
 *
 * <p>用 {@code record} 而非 {@code Map}：字段名、类型、可空性在编译期就确定，
 * 也便于 springdoc 生成 OpenAPI 文档。</p>
 *
 * @param message   用户输入（必填，非空白，UTF-8 不超过 32KB）
 * @param sessionId 会话 ID（可选）。缺省时按 {@code (web, userId, profile)} 复用同一活动会话；
 *                  显式传入但不存在时返回 404，避免把"拼错 ID"静默变成"新开会话"
 * @param profile   Profile 名（可选，默认 {@code default}）
 * @param userId    用户标识（可选，默认 {@code anonymous}）。同一 userId + profile 共享同一会话
 */
public record ChatRequest(String message, String sessionId, String profile, String userId) {
}
