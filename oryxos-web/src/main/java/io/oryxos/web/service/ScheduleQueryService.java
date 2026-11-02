package io.oryxos.web.service;

import io.oryxos.core.schedule.AgentScheduler;
import io.oryxos.core.schedule.ScheduledTaskInfo;
import io.oryxos.storage.entity.AgentRunEntity;
import io.oryxos.storage.repository.AgentRunRepository;
import io.oryxos.web.dto.ScheduleDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * ScheduleQueryService —— 把"注册态"和"运行史"拼成定时任务页的一份视图。
 *
 * <p>两个来源各管一半，缺一不可：</p>
 * <ul>
 *   <li><b>注册态</b>（内存）：cron、时区、下次触发时刻、此刻是否在跑、哪些配置被跳过。
 *       这些信息只存在于进程内，不落库——所以重启后要靠 Profile 重新注册。</li>
 *   <li><b>运行史</b>（{@code agent_runs}）：跑成什么样、耗时、token、成败。
 *       落库了，所以重启后仍然看得见。</li>
 * </ul>
 *
 * <p>刻意只读：不提供手动触发、暂停、改 cron 的入口。核心阶段"改配置要重启"是既定行为，
 * 给一个能在页面上临时改 cron 的按钮，等于制造两份真相。</p>
 */
@Service
public class ScheduleQueryService {

    /** 页面上最多展示几次最近的运行。 */
    private static final int RECENT_RUN_LIMIT = 3;

    private final AgentScheduler agentScheduler;
    private final AgentRunRepository agentRunRepository;
    private final int poolSize;

    public ScheduleQueryService(AgentScheduler agentScheduler,
                                AgentRunRepository agentRunRepository,
                                @Value("${oryxos.scheduler.pool-size:2}") int poolSize) {
        this.agentScheduler = agentScheduler;
        this.agentRunRepository = agentRunRepository;
        this.poolSize = Math.max(1, poolSize);
    }

    /** 当前定时任务全景：调度器汇总 + 每条任务 + 被跳过的配置。 */
    public ScheduleDtos.ScheduleView snapshot() {
        List<ScheduleDtos.ScheduleTask> tasks = agentScheduler.snapshot().stream()
                .map(this::toTask)
                .toList();

        List<ScheduleDtos.SkippedTask> skipped = agentScheduler.skippedTasks().stream()
                .map(item -> new ScheduleDtos.SkippedTask(
                        item.profileName(), item.id(), item.cron(), item.reason()))
                .toList();

        int running = (int) tasks.stream().filter(ScheduleDtos.ScheduleTask::running).count();
        return new ScheduleDtos.ScheduleView(
                new ScheduleDtos.Overview(poolSize, tasks.size(), running, skipped.size()),
                tasks,
                skipped);
    }

    private ScheduleDtos.ScheduleTask toTask(ScheduledTaskInfo info) {
        // 同一 Profile 的所有定时任务共用一条 scheduler 会话，所以运行史按 Profile 取
        List<AgentRunEntity> runs = agentRunRepository
                .findTop10ByChannelAndProfileNameOrderByStartedAtDesc(
                        AgentScheduler.CHANNEL, info.profileName());
        long runCount = agentRunRepository
                .countByChannelAndProfileName(AgentScheduler.CHANNEL, info.profileName());

        List<ScheduleDtos.RunBrief> recent = runs.stream()
                .limit(RECENT_RUN_LIMIT)
                .map(ScheduleQueryService::toRunBrief)
                .toList();

        return new ScheduleDtos.ScheduleTask(
                info.key(),
                info.profileName(),
                info.id(),
                info.cron(),
                info.timezone(),
                info.message(),
                info.running(),
                info.runningForMs(),
                info.nextRunAt() == null ? null : info.nextRunAt().toString(),
                runs.isEmpty() ? null : runs.get(0).getSessionId(),
                runCount,
                recent.isEmpty() ? null : recent.get(0),
                recent);
    }

    private static ScheduleDtos.RunBrief toRunBrief(AgentRunEntity run) {
        return new ScheduleDtos.RunBrief(
                run.getRunId(),
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
                text(run.getCompletedAt()));
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
}
