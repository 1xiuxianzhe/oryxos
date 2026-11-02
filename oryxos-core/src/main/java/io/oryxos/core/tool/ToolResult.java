package io.oryxos.core.tool;

/**
 * Tool 执行结果。
 */
public class ToolResult {

    private final boolean success;
    private final String content;
    private final String error;
    private final boolean retryable;

    public ToolResult(boolean success, String content, String error, boolean retryable) {
        this.success = success;
        this.content = content;
        this.error = error;
        this.retryable = retryable;
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null, false);
    }

    public static ToolResult fail(String error, boolean retryable) {
        return new ToolResult(false, null, error, retryable);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }

    public String getError() {
        return error;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
