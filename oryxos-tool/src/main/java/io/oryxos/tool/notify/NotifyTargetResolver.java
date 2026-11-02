package io.oryxos.tool.notify;

import io.oryxos.core.profile.Profile;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 从当前 Profile 解析出通知目标。
 *
 * <p>放在工具模块而非 {@code AgentContext}：{@code AgentContext} 在 core，
 * 而 {@link NotifyTarget} 在工具模块——若在 core 上开这个口子，core 就必须依赖工具模块的类型，
 * 属于反向依赖。依赖方向保持 tool → core。</p>
 *
 * <p>只按 {@code type} 匹配渠道，不引入"渠道名"这一层新概念：核心阶段的现实场景是
 * "一个 Profile 配一个群机器人"或"配几个不同类型的机器人"，用 type 足以区分。</p>
 */
public class NotifyTargetResolver {

    /** core 阶段支持的渠道类型。扩展阶段新增专用渠道时只改这里。 */
    public static final String WEBHOOK = "webhook";

    private static final List<String> SUPPORTED_TYPES = List.of(WEBHOOK);

    /**
     * 解析通知目标。
     *
     * @param profile     当前 Agent 配置；{@code null} 表示不在处理上下文中
     * @param channelName 期望的渠道类型；为 {@code null} / 空白时取第一个已配置渠道
     * @return 可直接交给适配器发送的通知目标
     * @throws NotifyConfigException 渠道未配置、不存在、类型不支持、或 url 缺失/非法
     */
    public NotifyTarget resolve(Profile profile, String channelName) {
        if (profile == null) {
            throw new NotifyConfigException("无法解析通知渠道：当前没有处理上下文");
        }

        List<Profile.NotifyChannelConfig> channels = profile.getNotifyChannels();
        if (channels == null || channels.isEmpty()) {
            throw new NotifyConfigException(
                    "当前 Agent（" + profile.getName() + "）未配置通知渠道 notify_channels");
        }

        Profile.NotifyChannelConfig chosen = select(channels, channelName);

        String type = chosen.getType();
        if (type == null || type.isBlank()) {
            throw new NotifyConfigException("通知渠道配置缺少 type");
        }
        if (!SUPPORTED_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            throw new NotifyConfigException(
                    "不支持的渠道类型 " + type + "，当前支持：" + SUPPORTED_TYPES);
        }

        Map<String, Object> rawConfig = chosen.getConfig();
        String url = readUrl(rawConfig);
        if (url == null || url.isBlank()) {
            throw new NotifyConfigException("渠道 " + type + " 缺少 " + WebhookNotifyAdapter.URL_KEY + " 配置");
        }
        validateUrl(type, url);

        return new NotifyTarget(type, flatten(rawConfig));
    }

    /** 缺省取第一个；指定时按 type 忽略大小写精确匹配。 */
    private Profile.NotifyChannelConfig select(List<Profile.NotifyChannelConfig> channels, String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return channels.get(0);
        }
        String wanted = channelName.trim();
        return channels.stream()
                .filter(config -> config.getType() != null && config.getType().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new NotifyConfigException(
                        "渠道 " + wanted + " 未配置，可用渠道：" + availableTypes(channels)));
    }

    private String availableTypes(List<Profile.NotifyChannelConfig> channels) {
        String types = channels.stream()
                .map(Profile.NotifyChannelConfig::getType)
                .filter(type -> type != null && !type.isBlank())
                .collect(Collectors.joining(", "));
        return types.isEmpty() ? "<无>" : "[" + types + "]";
    }

    private String readUrl(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        Object value = config.get(WebhookNotifyAdapter.URL_KEY);
        return value == null ? null : value.toString();
    }

    /**
     * 最低限度的形态校验：有 http/https scheme 且能解析出 host。
     *
     * <p>刻意不做可达性探测——解析阶段引入网络调用会让"配置错误"与"网络故障"两类问题混在一起。</p>
     */
    private void validateUrl(String type, String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new NotifyConfigException("渠道 " + type + " 的 url 非法: " + NotifyUrls.mask(url));
        }
        String scheme = uri.getScheme();
        boolean schemeOk = scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!schemeOk || uri.getHost() == null) {
            throw new NotifyConfigException("渠道 " + type + " 的 url 非法: " + NotifyUrls.mask(url));
        }
    }

    /** 配置值统一收敛为 String，发送侧不做类型分支。 */
    private Map<String, String> flatten(Map<String, Object> config) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (config != null) {
            config.forEach((key, value) -> flat.put(key, value == null ? null : value.toString()));
        }
        return flat;
    }
}
