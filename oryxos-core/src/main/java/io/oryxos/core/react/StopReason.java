package io.oryxos.core.react;

/**
 * 一次处理的停止原因（请求级审计的终态标记）。
 *
 * <p>前三项以正常返回值结束，后四项以异常方式结束（见 FR-018 / FR-019）。
 * 每次处理结束时既写日志，也落一条 {@code agent_runs} 记录，使运维与后台都能还原"为何停止"。</p>
 */
public enum StopReason {

    /** 模型未请求工具且给出了文本答复。 */
    FINAL_ANSWER,

    /** 模型既无文本也无工具请求，视为无效响应。 */
    EMPTY_RESPONSE,

    /** 达到最大轮数仍未收敛，强制收尾。 */
    MAX_ITERATIONS,

    /** 超过单次处理的整体耗时上限。 */
    TIMEOUT,

    /** 模型调用失败（网络/限流/鉴权/错误响应等）。 */
    MODEL_ERROR,

    /** 请求的 Agent 配置（Profile）不存在，处理尚未开始。 */
    AGENT_CONFIG_ERROR,

    /** 其他未预期异常（框架内部错误等）。 */
    INTERNAL_ERROR
}
