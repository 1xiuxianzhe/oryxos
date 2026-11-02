package io.oryxos.web.dto;

/**
 * 对话响应体。
 *
 * <p>字段沿用原占位实现的形状（{@code sessionId}/{@code role}/{@code content}/{@code status}），
 * 另外补一个 {@code profile}，方便调用方确认本次实际使用的 Agent 配置。</p>
 *
 * @param sessionId 会话 ID。调用方应保存它，下次请求带上即可接续同一段对话
 * @param profile   本次使用的 Profile 名
 * @param role      固定为 {@code assistant}
 * @param content   Agent 回复正文
 * @param status    固定为 {@code ok}；失败情况不走本响应体，由 GlobalExceptionHandler 返回错误结构
 */
public record ChatResponse(String sessionId, String profile, String role, String content, String status) {
}
