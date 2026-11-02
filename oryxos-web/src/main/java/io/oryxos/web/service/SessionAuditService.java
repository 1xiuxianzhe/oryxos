package io.oryxos.web.service;

import io.oryxos.core.session.SessionManager;
import io.oryxos.storage.entity.AgentRunEntity;
import io.oryxos.storage.entity.LlmCallEntity;
import io.oryxos.storage.entity.ToolInvocationEntity;
import io.oryxos.storage.repository.AgentRunRepository;
import io.oryxos.storage.repository.LlmCallRepository;
import io.oryxos.storage.repository.ToolInvocationRepository;
import io.oryxos.web.dto.SessionAuditDtos;
import io.oryxos.web.exception.SessionNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SessionAuditService —— 把三张审计表拼成"一次会话的完整账"。
 *
 * <p>为什么单独一层，而不是在 Controller 里直接查三个 Repository：</p>
 * <ol>
 *   <li>聚合逻辑（按 {@code run_id} 分组、按时间排序、汇总 token、截断长结果）需要被测试，
 *       塞进 Controller 只会让切片测试被迫 mock 三个仓储；</li>
 *   <li>Controller 保持"一次委托"，符合本轮定下的薄 Controller 约束；</li>
 *   <li>以后要加"按 Profile 汇总花费"这类视图，只动这一层。</li>
 * </ol>
 *
 * <p><b>数据来源是 SQLite，不是内存会话</b>：会话被归档、进程重启后依然查得到，
 * 这正是审计落库的价值。会话本身是否存在仍要校验——否则拼错 id 会返回一个"空账本"，
 * 让人误以为"这次对话什么都没花"。</p>
 */
@Service
public class SessionAuditService {

    /** 工具参数预览长度：够看清调了什么，不至于把整段 JSON 搬进列表。 */
    static final int PARAMETER_PREVIEW_CHARS = 500;

    /** 工具结果预览长度：读文件可能返回上百 KB，列表里只留开头。 */
    static final int RESULT_PREVIEW_CHARS = 2000;

    private static final Comparator<Instant> TIME_ASC =
            Comparator.nullsLast(Comparator.naturalOrder());

    private final SessionManager sessionManager;
    private final AgentRunRepository agentRunRepository;
    private final LlmCallRepository llmCallRepository;
    private final ToolInvocationRepository toolInvocationRepository;

    public SessionAuditService(SessionManager sessionManager,
                               AgentRunRepository agentRunRepository,
                               LlmCallRepository llmCallRepository,
                               ToolInvocationRepository toolInvocationRepository) {
        this.sessionManager = sessionManager;
        this.agentRunRepository = agentRunRepository;
        this.llmCallRepository = llmCallRepository;
        this.toolInvocationRepository = toolInvocationRepository;
    }

    /** 加载一次会话的全部运行明细；会话不存在 → 404 语义。 */
    public SessionAuditDtos.SessionAudit load(String sessionId) {
        if (sessionManager.get(sessionId) == null) {
            throw new SessionNotFoundException(sessionId);
        }

        List<AgentRunEntity> runs =
                agentRunRepository.findBySessionIdOrderByStartedAtDesc(sessionId);
        List<LlmCallEntity> llmCalls = llmCallRepository.findBySessionId(sessionId);
        List<ToolInvocationEntity> toolCalls = toolInvocationRepository.findBySessionId(sessionId);

        Map<String, List<SessionAuditDtos.LlmCallView>> llmByRun = llmCalls.stream()
                .filter(call -> call.getRunId() != null)
                .sorted(Comparator.comparing(LlmCallEntity::getStartedAt, TIME_ASC))
                .collect(Collectors.groupingBy(LlmCallEntity::getRunId,
                        Collectors.mapping(this::toLlmView, Collectors.toList())));

        Map<String, List<SessionAuditDtos.ToolCallView>> toolsByRun = toolCalls.stream()
                .filter(call -> call.getRunId() != null)
                .sorted(Comparator.comparing(ToolInvocationEntity::getStartedAt, TIME_ASC))
                .collect(Collectors.groupingBy(ToolInvocationEntity::getRunId,
                        Collectors.mapping(this::toToolView, Collectors.toList())));

        List<SessionAuditDtos.RunView> runViews = runs.stream()
                .map(run -> toRunView(run, llmByRun, toolsByRun))
                .toList();

        SessionAuditDtos.Totals totals = new SessionAuditDtos.Totals(
                runs.size(),
                llmCalls.size(),
                toolCalls.size(),
                sum(llmCalls.stream().map(LlmCallEntity::getPromptTokens).toList()),
                sum(llmCalls.stream().map(LlmCallEntity::getCompletionTokens).toList()),
                sum(llmCalls.stream().map(LlmCallEntity::getTotalTokens).toList()),
                runViews.stream().map(SessionAuditDtos.RunView::durationMs)
                        .filter(Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .sum());

        return new SessionAuditDtos.SessionAudit(sessionId, totals, runViews);
    }

    // ---------- 组装 ----------

    private SessionAuditDtos.RunView toRunView(
            AgentRunEntity run,
            Map<String, List<SessionAuditDtos.LlmCallView>> llmByRun,
            Map<String, List<SessionAuditDtos.ToolCallView>> toolsByRun) {
        String runId = run.getRunId();
        return new SessionAuditDtos.RunView(
                runId,
                run.getUserMessage(),
                run.getProfileName(),
                run.getChannel(),
                run.getProvider(),
                run.getModel(),
                run.getStatus(),
                run.getStopReason(),
                run.getSuccess(),
                run.getIterations(),
                run.getToolCallCount(),
                run.getPromptTokens(),
                run.getCompletionTokens(),
                run.getTotalTokens(),
                durationMs(run.getStartedAt(), run.getCompletedAt()),
                run.getErrorMessage(),
                text(run.getStartedAt()),
                text(run.getCompletedAt()),
                runId == null ? List.of() : llmByRun.getOrDefault(runId, List.of()),
                runId == null ? List.of() : toolsByRun.getOrDefault(runId, List.of()));
    }

    private SessionAuditDtos.LlmCallView toLlmView(LlmCallEntity call) {
        return new SessionAuditDtos.LlmCallView(
                call.getCallId(),
                call.getProvider(),
                call.getModel(),
                call.getPromptTokens(),
                call.getCompletionTokens(),
                call.getTotalTokens(),
                call.getLatencyMs(),
                call.getSuccess(),
                call.getErrorMessage(),
                text(call.getStartedAt()));
    }

    private SessionAuditDtos.ToolCallView toToolView(ToolInvocationEntity call) {
        String result = call.getResult();
        boolean truncated = result != null && result.length() > RESULT_PREVIEW_CHARS;
        return new SessionAuditDtos.ToolCallView(
                call.getInvocationId(),
                call.getToolName(),
                clip(call.getParameters(), PARAMETER_PREVIEW_CHARS),
                clip(result, RESULT_PREVIEW_CHARS),
                truncated,
                call.getLatencyMs(),
                call.getSuccess(),
                call.getErrorMessage(),
                text(call.getStartedAt()));
    }

    // ---------- 小工具 ----------

    private static long sum(List<Integer> values) {
        return values.stream().filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
    }

    private static Long durationMs(Instant startedAt, Instant completedAt) {
        if (startedAt == null || completedAt == null) {
            return null;
        }
        return Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    static String clip(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
