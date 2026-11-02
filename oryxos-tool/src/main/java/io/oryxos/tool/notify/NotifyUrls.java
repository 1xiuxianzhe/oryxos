package io.oryxos.tool.notify;

import java.net.URI;

/**
 * 通知地址的工具方法。
 *
 * <p>存在的理由是"脱敏"这件事要在多个地方做（解析、发送、错误信息），
 * 必须只有一份实现，避免某处漏掉把完整 webhook 地址打出去。</p>
 *
 * <p>群机器人 webhook 地址通常内嵌凭证（例如企业微信的 {@code ...?key=<密钥>}），
 * 因此对外暴露的文本只允许出现 host。</p>
 */
public final class NotifyUrls {

    private NotifyUrls() {
    }

    /** 提取 host；解析失败返回 {@code "<unknown>"}。 */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "<empty>";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? "<unknown>" : host;
        } catch (IllegalArgumentException e) {
            return "<unknown>";
        }
    }

    /**
     * 脱敏为 {@code scheme://host/***} 形式。
     *
     * <p>保留 scheme 与 host 便于定位问题，路径与查询串（凭证通常在这里）一律隐去。</p>
     */
    public static String mask(String url) {
        if (url == null || url.isBlank()) {
            return "<empty>";
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "***";
            }
            return scheme + "://" + host + "/***";
        } catch (IllegalArgumentException e) {
            return "***";
        }
    }
}
