package io.oryxos.web.controller;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.web.dto.CatalogDtos;
import io.oryxos.web.exception.ProfileNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ProfileApiController —— Agent（Profile）清单与详情。
 *
 * <p>数据来自 {@link ProfileRegistry}：这是"当前实例到底有哪些 Agent"的唯一真相来源。
 * 不硬编码、也不扫目录——两者都会与运行时实际可用的 Agent 不一致。</p>
 *
 * <p>详情里不返回 api-key（Profile 本来也不存密钥，密钥在全局 Provider 配置中）。</p>
 */
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileApiController {

    private final ProfileRegistry profileRegistry;

    public ProfileApiController(ProfileRegistry profileRegistry) {
        this.profileRegistry = profileRegistry;
    }

    /** Profile 列表，按名字排序（保证管理平台每次刷新顺序稳定）。 */
    @GetMapping
    public List<CatalogDtos.ProfileSummary> list() {
        return profileRegistry.getAllProfiles().values().stream()
                .sorted(Comparator.comparing(profile -> nullToEmpty(profile.getName())))
                .map(ProfileApiController::toSummary)
                .toList();
    }

    /** Profile 详情；不存在返回 404。 */
    @GetMapping("/{name}")
    public CatalogDtos.ProfileDetail detail(@PathVariable String name) {
        Profile profile = profileRegistry.getProfile(name);
        if (profile == null) {
            throw new ProfileNotFoundException(name);
        }
        Profile.ProviderConfig provider = profile.getProvider();
        return new CatalogDtos.ProfileDetail(
                profile.getName(),
                profile.getDescription(),
                profile.getIdentity(),
                provider == null ? null : provider.getName(),
                provider == null ? null : provider.getModel(),
                provider == null ? null : provider.getTemperature(),
                profile.getTools() == null ? List.of()
                        : profile.getTools().stream().map(Profile.ToolConfig::getName).toList(),
                profile.getSkills() == null ? List.of()
                        : profile.getSkills().stream().map(Profile.SkillConfig::getName).toList(),
                profile.getMcpServers() == null ? List.of()
                        : profile.getMcpServers().stream().map(Profile.McpServerConfig::getName).toList(),
                profile.getSchedules() == null ? List.of()
                        : profile.getSchedules().stream()
                        .map(schedule -> schedule.getId() + " @ " + schedule.getCron())
                        .toList(),
                profile.getSettings() == null ? Map.of() : profile.getSettings());
    }

    private static CatalogDtos.ProfileSummary toSummary(Profile profile) {
        Profile.ProviderConfig provider = profile.getProvider();
        return new CatalogDtos.ProfileSummary(
                profile.getName(),
                profile.getDescription(),
                provider == null ? null : provider.getName(),
                provider == null ? null : provider.getModel(),
                profile.getTools() == null ? 0 : profile.getTools().size(),
                profile.getSkills() == null ? 0 : profile.getSkills().size(),
                profile.getSchedules() == null ? 0 : profile.getSchedules().size());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
