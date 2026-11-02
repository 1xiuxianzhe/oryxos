package io.oryxos.web.exception;

/**
 * 请求里显式指定的会话 ID 不存在。
 *
 * <p>刻意不"顺手新建一个同 ID 的会话"：调用方多半是把 ID 拼错了，
 * 静默新建只会让他以为在接着聊，实际上下文是空的。宁可明确报 404。</p>
 */
public class SessionNotFoundException extends RuntimeException {

    private final String sessionId;

    public SessionNotFoundException(String sessionId) {
        super("会话不存在: '" + sessionId + "'。不传 sessionId 可按用户身份复用会话，或先调用 POST /api/v1/sessions 创建");
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
