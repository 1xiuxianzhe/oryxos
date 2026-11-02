package io.oryxos.web.exception;

/**
 * 会话已归档，仍尝试写入。
 *
 * <p>资源存在，但状态不允许写——所以是 400（调用方请求与当前状态冲突），不是 404。
 * 报 404 会让人以为"会话丢了"，反而去查存储。</p>
 */
public class SessionArchivedException extends RuntimeException {

    private final String sessionId;

    public SessionArchivedException(String sessionId) {
        super("会话已归档，不能继续发送消息: '" + sessionId + "'。请新建会话后继续");
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
