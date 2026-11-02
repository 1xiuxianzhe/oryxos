package io.oryxos.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Agent 管理（30 节）的传输模型。
 *
 * <p>与 {@link CatalogDtos} 的分工：那里是"只读仪表盘"，这里是"能管"——因此多了
 * 创建/更新/生成的请求体、文件清单与跳过原因。<b>仍然不返回绝对路径</b>：
 * 对外一律用 {@code agents/<name>/...} 这种相对工作区的写法，绝对路径只写进日志。</p>
 */
public final class AgentAdminDtos {

    private AgentAdminDtos() {
    }

    /** 新建 Agent。{@code description} 只进模板正文，不要求精确。 */
    public record CreateAgent(String name, String description) {
    }

    /** 覆盖 {@code AGENT.md} 全文。 */
    public record UpdateAgent(String markdown) {
    }

    /** 按描述生成草稿。 */
    public record GenerateRequest(String description) {
    }

    /** 保存一组文件：{@code 相对路径 → 内容}。 */
    public record SaveFiles(Map<String, String> files) {
    }

    /** Agent 专属会话的发消息请求。 */
    public record SendMessage(String content) {
    }

    /**
     * 列表项。
     *
     * @param fromDirectory 是否来自 {@code agents/} 目录（{@code false} 表示 legacy YAML）
     * @param skippedReason 加载失败的原因；正常为 {@code null}
     */
    public record AgentSummary(String name,
                               String description,
                               String provider,
                               String model,
                               int scheduleCount,
                               boolean fromDirectory,
                               String skippedReason) {
    }

    /** 启动/运行期被跳过的目录：配了却没生效，必须让人看得见。 */
    public record SkippedAgentView(String dir, String reason) {
    }

    /** Agent 列表 + 跳过清单。 */
    public record AgentListView(List<AgentSummary> agents, List<SkippedAgentView> skipped) {
    }

    /**
     * Agent 详情。
     *
     * @param dir   相对工作区的目录（{@code agents/<name>}）；legacy Profile 为 {@code null}
     * @param files 目录内文件清单（相对 Agent 目录；只给路径，不给内容）
     */
    public record AgentDetail(String name,
                              String description,
                              String provider,
                              String model,
                              List<String> tools,
                              List<String> schedules,
                              Map<String, Object> settings,
                              String dir,
                              List<String> files) {
    }

    /**
     * 生成结果：草稿文件 + 口径说明。
     *
     * @param note 说明"这些内容还没落盘"，避免前端误以为已生效
     */
    public record GeneratedFiles(Map<String, String> files, String note) {
    }

    /** 保存结果。 */
    public record WrittenFiles(String name, List<String> written) {
    }

    /** 删除结果：归档到哪一目了然。 */
    public record DeletedAgent(String name, String archivedTo) {
    }

    /** 按 Agent 的记忆快照。 */
    public record MemoryView(String content, int chars, String note) {
    }

    /** Agent 专属会话（固定三元组 {@code admin / console / <name>}）。 */
    public record AgentSession(String sessionId,
                               String agent,
                               List<SessionDtos.MessageView> messages,
                               int messageLimit) {
    }
}
