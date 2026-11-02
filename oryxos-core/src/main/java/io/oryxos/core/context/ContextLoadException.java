package io.oryxos.core.context;

/**
 * 上下文文件加载失败（Profile 显式引用的 Bootstrap 或 SKILL 文件缺失）。
 *
 * <p>刻意不静默跳过：静默会造成"人格悄悄丢了"这类最难查的软故障。</p>
 */
public class ContextLoadException extends RuntimeException {

    public ContextLoadException(String message) {
        super(message);
    }

    public ContextLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
