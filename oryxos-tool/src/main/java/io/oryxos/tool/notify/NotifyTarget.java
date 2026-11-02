package io.oryxos.tool.notify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知目标 —— 一次推送的"寄送地址"。
 *
 * <p>对外抽象中唯一携带"去哪儿"语义的载体：{@code channelType} 决定由哪一档实现解释
 * {@code config}，具体字段含义由实现类自行定义。接口层面不出现任何渠道特有词汇。</p>
 *
 * <p>值统一收敛为 {@code String}：HTTP 场景只需要字符串，避免在发送侧做类型分支。
 * 渠道特有的值类型转换由解析器一处完成。</p>
 *
 * @param channelType 渠道类型标识（如 {@code webhook}）
 * @param config      渠道特定配置（不可变）
 */
public record NotifyTarget(String channelType, Map<String, String> config) {

    public NotifyTarget {
        // 用 unmodifiableMap 而非 Map.copyOf：后者不接受 null 值，容错性太差
        config = config == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }

    /** 读取配置项；不存在返回 {@code null}。 */
    public String configValue(String key) {
        return config.get(key);
    }
}
