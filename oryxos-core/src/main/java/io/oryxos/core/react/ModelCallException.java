package io.oryxos.core.react;

/**
 * 模型调用失败（网络超时、限流、鉴权失败、错误响应等）。
 *
 * <p>循环把底层异常包成本类型后上抛（FR-018：不兜底、不伪装、不切换模型）。包一层的目的是让
 * 上游（{@code AgentService} / 各入口）能把"模型侧失败"与"系统内部缺陷"区分开——
 * 请求级审计里的 {@code stop_reason} 要如实写成 {@link StopReason#MODEL_ERROR}。</p>
 *
 * <p>原始异常与消息都完整保留：{@code getMessage()} 与底层一致，原始异常在 {@code getCause()}。</p>
 */
public class ModelCallException extends RuntimeException {

    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
