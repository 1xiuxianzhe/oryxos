package io.oryxos.tool.notify;

import io.oryxos.core.profile.Profile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** US2 验收：从当前 Profile 解析通知目标，含全部配置类失败分支。 */
class NotifyTargetResolverTest {

    private static final String WEBHOOK_URL = "https://qyapi.example.com/hook?key=SECRET123";

    private final NotifyTargetResolver resolver = new NotifyTargetResolver();

    private static Profile.NotifyChannelConfig channel(String type, Map<String, Object> config) {
        Profile.NotifyChannelConfig channel = new Profile.NotifyChannelConfig();
        channel.setType(type);
        channel.setConfig(config);
        return channel;
    }

    private static Profile profileOf(String name, List<Profile.NotifyChannelConfig> channels) {
        Profile profile = new Profile();
        profile.setName(name);
        profile.setNotifyChannels(channels);
        return profile;
    }

    @Test
    void 未指定渠道时取第一个() {
        Profile profile = profileOf("demo", List.of(
                channel("webhook", Map.of("url", WEBHOOK_URL)),
                channel("webhook", Map.of("url", "https://second.example.com/hook"))));

        NotifyTarget target = resolver.resolve(profile, null);

        assertThat(target.channelType()).isEqualTo("webhook");
        assertThat(target.configValue("url")).isEqualTo(WEBHOOK_URL);
    }

    @Test
    void 按类型匹配渠道且忽略大小写() {
        Profile profile = profileOf("demo", List.of(
                channel("webhook", Map.of("url", WEBHOOK_URL))));

        assertThat(resolver.resolve(profile, "WebHook").configValue("url")).isEqualTo(WEBHOOK_URL);
    }

    @Test
    void 解析结果把配置值统一收敛为字符串() {
        Profile profile = profileOf("demo", List.of(
                channel("webhook", Map.of("url", WEBHOOK_URL, "retry", 3))));

        NotifyTarget target = resolver.resolve(profile, null);

        assertThat(target.config()).containsEntry("retry", "3");
        assertThat(target.config()).isUnmodifiable();
    }

    @Test
    void 没有处理上下文时明确失败() {
        assertThatThrownBy(() -> resolver.resolve(null, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("没有处理上下文");
    }

    @Test
    void 未配置通知渠道时明确失败() {
        Profile profile = profileOf("demo", null);

        assertThatThrownBy(() -> resolver.resolve(profile, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("未配置通知渠道");
    }

    @Test
    void 渠道列表为空时明确失败() {
        Profile profile = profileOf("demo", List.of());

        assertThatThrownBy(() -> resolver.resolve(profile, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("未配置通知渠道");
    }

    @Test
    void 指定渠道不存在时列出可用渠道() {
        Profile profile = profileOf("demo", List.of(
                channel("webhook", Map.of("url", WEBHOOK_URL))));

        assertThatThrownBy(() -> resolver.resolve(profile, "feishu"))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("渠道 feishu 未配置")
                .hasMessageContaining("webhook");
    }

    @Test
    void 渠道类型缺失时明确失败() {
        Profile profile = profileOf("demo", List.of(channel(null, Map.of("url", WEBHOOK_URL))));

        assertThatThrownBy(() -> resolver.resolve(profile, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("缺少 type");
    }

    @Test
    void 不支持的渠道类型被拒绝而不是按webhook处理() {
        Profile profile = profileOf("demo", List.of(
                channel("feishu", Map.of("url", WEBHOOK_URL))));

        assertThatThrownBy(() -> resolver.resolve(profile, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("不支持的渠道类型 feishu");
    }

    @Test
    void 缺少url配置时明确失败() {
        Profile profile = profileOf("demo", List.of(channel("webhook", Map.of("token", "t"))));

        assertThatThrownBy(() -> resolver.resolve(profile, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("缺少 url 配置");
    }

    @Test
    void config为空时明确失败() {
        Profile profile = profileOf("demo", List.of(channel("webhook", null)));

        assertThatThrownBy(() -> resolver.resolve(profile, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("缺少 url 配置");
    }

    @Test
    void url非法时明确失败且错误信息不含完整地址() {
        Profile profile = profileOf("demo", List.of(channel("webhook", Map.of("url", "not-a-url"))));

        assertThatThrownBy(() -> resolver.resolve(profile, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("url 非法");
    }

    @Test
    void 非http协议的url被拒绝() {
        Profile profile = profileOf("demo", List.of(
                channel("webhook", Map.of("url", "ftp://example.com/hook"))));

        assertThatThrownBy(() -> resolver.resolve(profile, null))
                .isInstanceOf(NotifyConfigException.class)
                .hasMessageContaining("url 非法");
    }
}
