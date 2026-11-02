package io.oryxos.core.tool;

import java.util.Map;

/**
 * 工具执行前的前置校验扩展点。
 *
 * <p>{@code ToolExecutor} 在真正执行前必经此校验；具体规则（Sandbox 的 Path/Pattern 白名单）
 * 由后续 Sandbox 能力提供实现，本特性只保证调用点存在且位于"执行之前"。</p>
 *
 * <p>校验不通过时抛异常，由 {@code ToolExecutor} 捕获为失败结果（不中断整个处理）。</p>
 */
public interface ToolInvocationValidator {

    /**
     * 执行前校验。
     *
     * @param tool  即将被执行的工具
     * @param input 已解析的入参
     */
    void validate(OryxTool tool, Map<String, Object> input);
}
