package io.oryxos.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 查询类传输模型：Profile / Provider / Tool / Memory / Info。
 *
 * <p>共同约束：<b>不返回 api-key、不返回绝对路径、不返回白名单内容</b>。
 * 管理平台是只读仪表盘，不是配置编辑器——它能回答"有什么、开没开、几条"，
 * 不能回答"具体配了哪条路径"。</p>
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    /** Profile（Agent）摘要，用于列表与统计。 */
    public record ProfileSummary(String name,
                                 String description,
                                 String provider,
                                 String model,
                                 int toolCount,
                                 int skillCount,
                                 int scheduleCount) {
    }

    /** Profile 详情：只读展示，不包含密钥。 */
    public record ProfileDetail(String name,
                                String description,
                                String identity,
                                String provider,
                                String model,
                                Double temperature,
                                List<String> tools,
                                List<String> skills,
                                List<String> mcpServers,
                                List<String> schedules,
                                Map<String, Object> settings) {
    }

    /**
     * Provider 摘要。
     *
     * @param status {@code configured} 表示已装配出 ChatModel；{@code not-assembled} 表示配置里有但没生效
     */
    public record ProviderSummary(String name, String baseUrl, String status) {
    }

    /** 工具摘要。 */
    public record ToolSummary(String name, String description) {
    }

    /**
     * 长期记忆快照。
     *
     * @param note 口径说明（归档区截断），避免把"注入视角"误读成磁盘原文
     */
    public record MemorySnapshot(String content, int chars, String note) {
    }

    /**
     * 系统信息。
     *
     * @param canBuildAgents 当前是否至少装配了一个 Provider（false 时 Agent 调用必然失败）
     */
    public record InfoResponse(String name,
                               String version,
                               String description,
                               boolean canBuildAgents,
                               List<ProviderSummary> providers,
                               SandboxPolicy sandbox) {
    }

    /**
     * 沙箱策略摘要：<b>只给开关与条数</b>。
     *
     * <p>当前没有认证，把路径 / 命令 / 域名清单吐出来等于把安全边界画给所有人看；
     * 而"沙箱开没开、配了几条"已经能回答运维最关心的问题。</p>
     */
    public record SandboxPolicy(boolean enabled, int allowedPaths, int allowedCommands, int allowedDomains) {
    }
}
