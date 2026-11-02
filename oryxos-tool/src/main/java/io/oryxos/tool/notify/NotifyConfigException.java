package io.oryxos.tool.notify;

/**
 * 通知渠道配置类失败：未配置渠道、渠道不存在、类型不支持、缺少或非法的 url。
 *
 * <p>这类失败重试无用，工具层应把它转成 {@code retryable=false} 的结果。</p>
 */
public class NotifyConfigException extends RuntimeException {

    public NotifyConfigException(String message) {
        super(message);
    }
}
