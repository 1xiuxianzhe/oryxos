package io.oryxos.web.dto;

import java.util.List;

/**
 * 定时任务页的传输模型。
 *
 * <p>数据来自两处：注册态的实时快照（内存里的 {@code AgentScheduler}）与运行历史
 * （SQLite 的 {@code agent_runs}）。前者回答"会不会跑、下次什么时候跑、此刻在不在跑"，
 * 后者回答"跑成什么样"。</p>
 *
 * <p><b>口径说明</b>：运行历史按 Profile 聚合。同一 Profile 的所有定时任务共用一条
 * {@code ("scheduler","scheduler",profile)} 会话，{@code agent_runs} 里没有任务级字段，
 * 所以"最近运行"是这段会话的最近几次请求，而不是"这个任务"的。宁可说明白，也不编造。</p>
 */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    /** 调度器整体状态。 */
    public record Overview(int poolSize,
                           int registered,
                           int running,
                           int skipped) {
    }

    /** 一次定时触发的运行摘要。 */
    public record RunBrief(String runId,
                           String status,
                           String stopReason,
                           Boolean success,
                           Integer iterations,
                           Integer toolCallCount,
                           Integer promptTokens,
                           Integer completionTokens,
                           Integer totalTokens,
                           Long durationMs,
                           String errorMessage,
                           String startedAt,
                           String completedAt) {
    }

    /**
     * 一条定时任务。
     *
     * @param running      此刻是否正在执行（占着防重叠锁）
     * @param runningForMs 已执行时长；未运行时为 null
     * @param nextRunAt    下一次触发时刻（ISO-8601）；算不出来时为 null
     * @param sessionId    该 Profile 的定时会话；从未触发过时为 null
     * @param runCount     该 Profile 累计定时触发次数（不是本任务的）
     */
    public record ScheduleTask(String key,
                               String profileName,
                               String id,
                               String cron,
                               String timezone,
                               String message,
                               boolean running,
                               Long runningForMs,
                               String nextRunAt,
                               String sessionId,
                               long runCount,
                               RunBrief lastRun,
                               List<RunBrief> recentRuns) {
    }

    /** 注册阶段被跳过的配置：配了却没生效，必须在页面上看得见。 */
    public record SkippedTask(String profileName, String id, String cron, String reason) {
    }

    /** 定时任务页的完整视图。 */
    public record ScheduleView(Overview scheduler,
                               List<ScheduleTask> tasks,
                               List<SkippedTask> skipped) {
    }
}
