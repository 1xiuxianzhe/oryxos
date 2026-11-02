package io.oryxos.tool.sandbox;

/**
 * 沙箱校验失败异常。任意校验失败抛此异常，Tool 执行终止。
 */
public class SandboxViolationException extends RuntimeException {

    public SandboxViolationException(String message) {
        super(message);
    }
}
