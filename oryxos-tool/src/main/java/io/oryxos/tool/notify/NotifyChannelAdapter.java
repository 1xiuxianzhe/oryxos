package io.oryxos.tool.notify;

/**
 * 通知渠道适配器 —— "把一段内容送到某个通知目标"的抽象。
 *
 * <p><b>接口中立性</b>：方法名、参数名、参数类型中都不得出现具体渠道或协议词汇。
 * 自查方式——把实现换成企业微信官方 SDK 版本，这个签名是否还需要改？答案必须是不需要。
 * 新增一档渠道只新增实现类，不改本接口、不改调用方。</p>
 *
 * <p><b>失败语义</b>：任何非成功结果（网络异常、超时、目标拒绝）都必须抛异常，
 * 不得静默返回。方法返回 {@code void}，不暴露渠道特有的响应结构。</p>
 */
public interface NotifyChannelAdapter {

    /**
     * 发送一条通知。
     *
     * @param target  通知目标（含渠道类型与渠道配置）
     * @param content 要推送的内容
     * @throws NotifyConfigException 目标配置不足以完成发送（如缺少 url）
     * @throws NotifySendException   发送失败（网络异常 / 非 2xx）
     */
    void send(NotifyTarget target, String content);
}
