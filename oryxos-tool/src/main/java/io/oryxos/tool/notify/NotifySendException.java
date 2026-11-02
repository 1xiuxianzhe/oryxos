package io.oryxos.tool.notify;

/**
 * 通知发送失败（网络异常 / 超时 / 目标返回非 2xx）。
 *
 * <p>携带可重试标记：网络类与 5xx 可重试，目标返回 4xx 不可重试。</p>
 */
public class NotifySendException extends RuntimeException {

    private final boolean retryable;

    public NotifySendException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public NotifySendException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
