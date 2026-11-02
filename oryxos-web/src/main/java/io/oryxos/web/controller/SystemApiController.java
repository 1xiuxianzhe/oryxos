package io.oryxos.web.controller;

import io.oryxos.provider.ProviderModelRegistry;
import io.oryxos.provider.config.OryxosProviderProperties;
import io.oryxos.tool.sandbox.SandboxProperties;
import io.oryxos.web.dto.CatalogDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * SystemApiController —— 健康与系统信息。
 *
 * <p>{@code /health} 只回答"进程活着"；{@code /info} 回答"这个实例是什么、能不能干活"：</p>
 * <ul>
 *   <li>Provider 清单与装配状态——{@code canBuildAgents=false} 时任何 Agent 调用都必然失败，
 *       这是排障第一眼要看的东西；</li>
 *   <li>沙箱策略<b>只给开关与三类条数</b>：当前无认证，白名单内容不外露。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class SystemApiController {

    private final OryxosProviderProperties properties;
    private final ProviderModelRegistry modelRegistry;
    private final SandboxProperties sandboxProperties;
    private final String version;

    public SystemApiController(OryxosProviderProperties properties,
                               ProviderModelRegistry modelRegistry,
                               SandboxProperties sandboxProperties,
                               @Value("${oryxos.version:0.1.0}") String version) {
        this.properties = properties;
        this.modelRegistry = modelRegistry;
        this.sandboxProperties = sandboxProperties;
        this.version = version;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/info")
    public CatalogDtos.InfoResponse info() {
        List<CatalogDtos.ProviderSummary> providers = properties.getProviders().stream()
                .map(provider -> new CatalogDtos.ProviderSummary(
                        provider.getName(),
                        provider.getBaseUrl(),
                        modelRegistry.contains(provider.getName())
                                ? ProviderApiController.STATUS_CONFIGURED
                                : ProviderApiController.STATUS_NOT_ASSEMBLED))
                .sorted(Comparator.comparing(CatalogDtos.ProviderSummary::name,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        CatalogDtos.SandboxPolicy sandbox = new CatalogDtos.SandboxPolicy(
                sandboxProperties.isEnabled(),
                sandboxProperties.getAllowedPaths().size(),
                sandboxProperties.getAllowedCommands().size(),
                sandboxProperties.getAllowedDomains().size());

        return new CatalogDtos.InfoResponse(
                "OryxOS",
                version,
                "面向企业场景的 Agent OS 运行时内核",
                modelRegistry.size() > 0,
                providers,
                sandbox);
    }
}
