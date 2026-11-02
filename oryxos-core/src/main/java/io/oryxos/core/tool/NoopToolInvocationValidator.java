package io.oryxos.core.tool;

import java.util.Map;

/**
 * 默认的校验实现：全部放行。用于尚未接入 Sandbox 能力的场景。
 */
public final class NoopToolInvocationValidator implements ToolInvocationValidator {

    public static final NoopToolInvocationValidator INSTANCE = new NoopToolInvocationValidator();

    private NoopToolInvocationValidator() {
    }

    @Override
    public void validate(OryxTool tool, Map<String, Object> input) {
        // 全部放行
    }
}
