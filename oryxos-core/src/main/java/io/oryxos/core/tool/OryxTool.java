package io.oryxos.core.tool;

import java.util.Map;

/**
 * OryxOS 内部统一的 Tool 抽象接口。
 * 内置 Tool、@Tool 注解的 Plugin Tool、MCP Tool 都被包装成 OryxTool 实例注册到 ToolRegistry，
 * ReAct 循环不感知具体 Tool 的来源。
 */
public interface OryxTool {

    /** Tool 名称（全局唯一，供 LLM Function Calling 引用）。 */
    String getName();

    /** Tool 描述（写入 prompt，供 LLM 理解何时调用）。 */
    String getDescription();

    /** 输入参数的 JSON Schema。 */
    String getInputSchema();

    /**
     * 执行 Tool。
     *
     * @param input 参数（JSON 形式）
     * @return 执行结果
     */
    ToolResult execute(Map<String, Object> input);
}
