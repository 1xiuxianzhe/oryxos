package io.oryxos.core.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Set;

/**
 * ProfileValidator —— 两条来源（legacy {@code profiles/*.yaml} 与 Agent 目录）共用的唯一校验实现。
 *
 * <p><b>为什么必须抽出来</b>：spec FR-006 要求"同一非法配置在两条路径上报出同一异常类型与同一消息"。
 * 只要两处各写一遍校验，第一条规则改动就会让两边漂移，而这种漂移在单个来源的测试里看不出来。</p>
 *
 * <p><b>关于"缺 provider 段"</b>：历史实现只 WARN 并继续注册，结果是这个 Agent 能出现在列表里、
 * 却永远调不动模型。收敛结论（spec C-14 / A-021）是把两条来源一起收紧为硬错——
 * 宁可启动时点名失败，也不要留一个"看着在、跑不动"的 Agent。</p>
 */
public final class ProfileValidator {

    private static final Logger logger = LoggerFactory.getLogger(ProfileValidator.class);

    /** 全局层声明的 provider 名；为 {@code null} 表示不做引用校验（便于独立测试）。 */
    private final Set<String> availableProviders;

    public ProfileValidator(Collection<String> availableProviders) {
        this.availableProviders = availableProviders == null ? null : Set.copyOf(availableProviders);
    }

    /**
     * 校验一个 Profile。
     *
     * @throws ProfileValidationException provider 段缺失、provider.name 为空、或引用了未声明的 provider
     */
    public void validate(Profile profile) {
        String owner = profile.getName() == null ? "<未命名>" : profile.getName();

        Profile.ProviderConfig provider = profile.getProvider();
        if (provider == null) {
            throw new ProfileValidationException(
                    "缺少 provider 段（Agent [" + owner + "]）：没有 provider 就无法调用模型");
        }

        String providerName = provider.getName();
        if (providerName == null || providerName.isBlank()) {
            throw new ProfileValidationException(
                    "provider.name 不能为空（Profile [" + owner + "]）");
        }

        if (availableProviders != null && !availableProviders.contains(providerName)) {
            throw new ProfileValidationException(
                    "Provider '" + providerName + "' 未在全局层 oryxos.providers 中声明（可用: "
                            + availableProviders + "）");
        }

        if (provider.getModel() == null || provider.getModel().isBlank()) {
            logger.warn("Profile [{}] 未指定 provider.model", owner);
        }
        if (provider.getTemperature() == null) {
            logger.debug("Profile [{}] 未指定 provider.temperature，将使用默认值", owner);
        }
    }
}
