package io.oryxos.web.controller;

import io.oryxos.provider.ProviderModelRegistry;
import io.oryxos.provider.config.OryxosProviderProperties;
import io.oryxos.web.dto.CatalogDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * ProviderApiController —— 本实例接了哪些 LLM Provider。
 *
 * <p>数据来自全局配置（{@link OryxosProviderProperties}）与装配结果
 * （{@link ProviderModelRegistry}）：前者说"配了什么"，后者说"真的装配出来了什么"。
 * 两者不一致（配置里有、注册表里没有）正是最值得暴露的部署错误，所以状态给
 * {@code configured} / {@code not-assembled} 两档。</p>
 *
 * <p><b>绝不返回 api-key</b>：管理平台是只读仪表盘，没有任何理由看到凭证。</p>
 */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderApiController {

    static final String STATUS_CONFIGURED = "configured";
    static final String STATUS_NOT_ASSEMBLED = "not-assembled";

    private final OryxosProviderProperties properties;
    private final ProviderModelRegistry modelRegistry;

    public ProviderApiController(OryxosProviderProperties properties, ProviderModelRegistry modelRegistry) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
    }

    @GetMapping
    public List<CatalogDtos.ProviderSummary> list() {
        return properties.getProviders().stream()
                .map(provider -> new CatalogDtos.ProviderSummary(
                        provider.getName(),
                        provider.getBaseUrl(),
                        modelRegistry.contains(provider.getName()) ? STATUS_CONFIGURED : STATUS_NOT_ASSEMBLED))
                .sorted(Comparator.comparing(CatalogDtos.ProviderSummary::name,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
