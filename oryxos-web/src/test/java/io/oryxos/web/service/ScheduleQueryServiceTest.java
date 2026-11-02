package io.oryxos.web.service;

import io.oryxos.core.schedule.AgentScheduler;
import io.oryxos.core.schedule.ScheduledTaskInfo;
import io.oryxos.core.schedule.SkippedTask;
import io.oryxos.storage.entity.AgentRunEntity;
import io.oryxos.storage.repository.AgentRunRepository;
import io.oryxos.web.dto.ScheduleDtos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 定时任务视图的组装验收：注册态与运行史拼得对、没跑过时不编数据。
 */
class ScheduleQueryServiceTest {

    private static final String PROFILE = "smoke";

    private final AgentScheduler scheduler = mock(AgentScheduler.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);

    private final ScheduleQueryService service = new ScheduleQueryService(scheduler, runs, 3);

    private static ScheduledTaskInfo task(boolean running, Long runningForMs) {
        return new ScheduledTaskInfo(
                PROFILE + "#daily-brief", PROFILE, "daily-brief",
                "0 0 3 * * *", "Asia/Shanghai", "读 HELLO.txt 并给出摘要",
                running, runningForMs, Instant.parse("2026-10-23T19:00:00Z"));
    }

    private static AgentRunEntity run(String runId, Instant startedAt, Instant completedAt) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(runId);
        entity.setSessionId("sess-1");
        entity.setProfileName(PROFILE);
        entity.setChannel(AgentScheduler.CHANNEL);
        entity.setUserId(AgentScheduler.USER_ID);
        entity.setStatus("FINISHED");
        entity.setStopReason("FINAL_ANSWER");
        entity.setSuccess(true);
        entity.setIterations(2);
        entity.setToolCallCount(1);
        entity.setPromptTokens(250);
        entity.setCompletionTokens(50);
        entity.setTotalTokens(300);
        entity.setStartedAt(startedAt);
        entity.setCompletedAt(completedAt);
        return entity;
    }

    @Test
    void 注册态与运行史组装成一份视图() {
        when(scheduler.snapshot()).thenReturn(List.of(task(false, null)));
        when(scheduler.skippedTasks()).thenReturn(List.of());
        when(runs.findTop10ByChannelAndProfileNameOrderByStartedAtDesc(
                AgentScheduler.CHANNEL, PROFILE)).thenReturn(List.of(
                run("r-3", Instant.parse("2026-10-22T19:00:00Z"), Instant.parse("2026-10-22T19:00:02Z")),
                run("r-2", Instant.parse("2026-10-21T19:00:00Z"), Instant.parse("2026-10-21T19:00:01Z")),
                run("r-1", Instant.parse("2026-10-20T19:00:00Z"), Instant.parse("2026-10-20T19:00:03Z"))));
        when(runs.countByChannelAndProfileName(AgentScheduler.CHANNEL, PROFILE)).thenReturn(5L);

        ScheduleDtos.ScheduleView view = service.snapshot();

        assertThat(view.scheduler().poolSize()).isEqualTo(3);
        assertThat(view.scheduler().registered()).isEqualTo(1);
        assertThat(view.scheduler().running()).isZero();
        assertThat(view.scheduler().skipped()).isZero();

        ScheduleDtos.ScheduleTask task = view.tasks().get(0);
        assertThat(task.key()).isEqualTo("smoke#daily-brief");
        assertThat(task.cron()).isEqualTo("0 0 3 * * *");
        assertThat(task.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(task.nextRunAt()).isEqualTo("2026-10-23T19:00:00Z");
        assertThat(task.sessionId()).isEqualTo("sess-1");
        assertThat(task.runCount()).isEqualTo(5);
        assertThat(task.lastRun().runId()).isEqualTo("r-3");
        assertThat(task.lastRun().durationMs()).isEqualTo(2000);
        assertThat(task.recentRuns()).hasSize(3);
    }

    @Test
    void 从未触发时不给假数据() {
        when(scheduler.snapshot()).thenReturn(List.of(task(false, null)));
        when(scheduler.skippedTasks()).thenReturn(List.of());
        when(runs.findTop10ByChannelAndProfileNameOrderByStartedAtDesc(
                AgentScheduler.CHANNEL, PROFILE)).thenReturn(List.of());
        when(runs.countByChannelAndProfileName(AgentScheduler.CHANNEL, PROFILE)).thenReturn(0L);

        ScheduleDtos.ScheduleTask task = service.snapshot().tasks().get(0);

        assertThat(task.runCount()).isZero();
        assertThat(task.sessionId()).isNull();
        assertThat(task.lastRun()).isNull();
        assertThat(task.recentRuns()).isEmpty();
    }

    @Test
    void 运行中的任务计入running且带已运行时长() {
        when(scheduler.snapshot()).thenReturn(List.of(task(true, 12000L)));
        when(scheduler.skippedTasks()).thenReturn(List.of());
        when(runs.findTop10ByChannelAndProfileNameOrderByStartedAtDesc(
                AgentScheduler.CHANNEL, PROFILE)).thenReturn(List.of());
        when(runs.countByChannelAndProfileName(AgentScheduler.CHANNEL, PROFILE)).thenReturn(0L);

        ScheduleDtos.ScheduleView view = service.snapshot();

        assertThat(view.scheduler().running()).isEqualTo(1);
        assertThat(view.tasks().get(0).running()).isTrue();
        assertThat(view.tasks().get(0).runningForMs()).isEqualTo(12000L);
    }

    @Test
    void 被跳过的配置原样透出原因() {
        when(scheduler.snapshot()).thenReturn(List.of());
        when(scheduler.skippedTasks()).thenReturn(List.of(
                new SkippedTask("broken", "bad-cron", "not-a-cron", "cron 非法: not-a-cron")));

        ScheduleDtos.ScheduleView view = service.snapshot();

        assertThat(view.scheduler().skipped()).isEqualTo(1);
        assertThat(view.skipped()).hasSize(1);
        assertThat(view.skipped().get(0).profileName()).isEqualTo("broken");
        assertThat(view.skipped().get(0).reason()).contains("cron 非法");
    }
}
