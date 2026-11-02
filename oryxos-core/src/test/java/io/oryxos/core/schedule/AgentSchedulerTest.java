package io.oryxos.core.schedule;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 定时任务契约：注册参数、防重叠、失败隔离、会话身份、配置校验。
 *
 * <p>见 {@code specs/008-scheduler/contracts/scheduler.md} 第 5 节验证项 1 ~ 11。</p>
 *
 * <p>测试诀窍：<b>别真等时间</b>。注册阶段用 mock {@link TaskScheduler} 抓参数；
 * 触发阶段直接调 {@link AgentScheduler#runOnce}。</p>
 */
class AgentSchedulerTest {

    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final AgentService agentService = mock(AgentService.class);
    private final SessionManager sessionManager = new SessionManager();
    private final ProfileRegistry profileRegistry = new ProfileRegistry();

    private AgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AgentScheduler(taskScheduler, profileRegistry, sessionManager, agentService);
        doReturn(mock(ScheduledFuture.class)).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    // ---------- 构造工具 ----------

    private static Profile.ScheduleConfig schedule(String id, String cron, String zone, String message) {
        Profile.ScheduleConfig config = new Profile.ScheduleConfig();
        config.setId(id);
        config.setCron(cron);
        config.setTimezone(zone);
        config.setMessage(message);
        return config;
    }

    private static Profile profile(String name, Profile.ScheduleConfig... schedules) {
        Profile profile = new Profile();
        profile.setName(name);
        profile.setSchedules(List.of(schedules));
        return profile;
    }

    private Trigger capturedTrigger() {
        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler, atLeastOnce()).schedule(any(Runnable.class), captor.capture());
        return captor.getValue();
    }

    /** 用固定时钟算"下一次执行时刻"，这是时区真的生效的证据（比断言 toString 可靠）。 */
    private static Instant nextExecution(Trigger trigger, Instant now) {
        return trigger.nextExecution(new TriggerContext() {
            @Override
            public Instant lastScheduledExecution() {
                return null;
            }

            @Override
            public Instant lastActualExecution() {
                return null;
            }

            @Override
            public Instant lastCompletion() {
                return null;
            }

            @Override
            public Clock getClock() {
                return Clock.fixed(now, ZoneOffset.UTC);
            }
        });
    }

    // ---------- 注册 ----------

    @Test
    void 注册时cron与时区一起交给CronTrigger() {
        profileRegistry.registerProfile("daily",
                profile("daily", schedule("brief", "0 0 9 * * *", "Asia/Shanghai", "汇总昨天的 PR")));

        int registered = scheduler.registerAll();

        assertThat(registered).isEqualTo(1);
        assertThat(scheduler.registeredTasks()).containsExactly("daily#brief");

        Trigger trigger = capturedTrigger();
        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(((CronTrigger) trigger).getExpression()).isEqualTo("0 0 9 * * *");

        // 东八区 9 点 = UTC 01:00；若时区没生效，这里会是 09:00Z
        assertThat(nextExecution(trigger, Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-01-01T01:00:00Z"));
    }

    @Test
    void 未配时区时使用系统时区() {
        profileRegistry.registerProfile("daily",
                profile("daily", schedule("brief", "0 0 9 * * *", null, "hi")));

        scheduler.registerAll();

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        CronTrigger expected = new CronTrigger("0 0 9 * * *", ZoneId.systemDefault());
        assertThat(nextExecution(capturedTrigger(), now)).isEqualTo(nextExecution(expected, now));
    }

    @Test
    void 没有定时配置时注册零条且不碰调度器() {
        profileRegistry.registerProfile("plain", profile("plain"));

        assertThat(scheduler.registerAll()).isZero();
        assertThat(scheduler.registeredTasks()).isEmpty();
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void 注册失败时schedule返回null不算成功() {
        doReturn(null).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        profileRegistry.registerProfile("daily",
                profile("daily", schedule("brief", "0 0 9 * * *", "UTC", "hi")));

        assertThat(scheduler.registerAll()).isZero();
        assertThat(scheduler.registeredTasks()).isEmpty();
    }

    // ---------- US1：触发走同一入口 ----------

    @Test
    void 触发一次调用AgentService一次且消息与配置一致() {
        Profile profile = profile("daily", schedule("brief", "0 0 9 * * *", "UTC", "汇总昨天的 PR"));

        scheduler.runOnce(profile, profile.getSchedules().get(0));

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentService).process(sessionCaptor.capture(), messageCaptor.capture());

        assertThat(messageCaptor.getValue()).isEqualTo("汇总昨天的 PR");
        Session session = sessionCaptor.getValue();
        assertThat(session.getChannel()).isEqualTo(AgentScheduler.CHANNEL);
        assertThat(session.getUserId()).isEqualTo(AgentScheduler.USER_ID);
        assertThat(session.getProfileName()).isEqualTo("daily");
    }

    @Test
    void 同一Profile两次触发复用同一个Session() {
        Profile profile = profile("daily", schedule("brief", "0 0 9 * * *", "UTC", "hi"));

        scheduler.runOnce(profile, profile.getSchedules().get(0));
        scheduler.runOnce(profile, profile.getSchedules().get(0));

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(agentService, times(2)).process(captor.capture(), anyString());
        assertThat(captor.getAllValues().get(0).getSessionId())
                .isEqualTo(captor.getAllValues().get(1).getSessionId());
    }

    @Test
    void message缺失时回退兼容字段task() {
        Profile.ScheduleConfig legacy = schedule("legacy", "0 0 9 * * *", "UTC", null);
        legacy.setTask("旧字段里的任务描述");
        Profile profile = profile("daily", legacy);

        scheduler.runOnce(profile, legacy);

        verify(agentService).process(any(Session.class), org.mockito.ArgumentMatchers.eq("旧字段里的任务描述"));
    }

    // ---------- US2：防重叠 ----------

    @Test
    void 上一次还没跑完_本次触发直接跳过() throws InterruptedException {
        Profile profile = profile("daily", schedule("brief", "0 0 9 * * *", "UTC", "hi"));

        // 占锁必须来自"另一个线程"：ReentrantLock 允许同线程重入，
        // 在测试线程里 lock() 再调 runOnce，tryLock 会成功——那就测不到"跳过"了
        Lock lock = scheduler.lockFor("daily", "brief");
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            acquired.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "scheduler-test-holder");
        holder.start();
        assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            scheduler.runOnce(profile, profile.getSchedules().get(0));

            verify(agentService, never()).process(any(Session.class), anyString());
        } finally {
            release.countDown();
            holder.join(5_000);
        }
    }

    @Test
    void 不同Profile的同名任务互不阻塞() {
        Profile a = profile("a", schedule("daily", "0 0 9 * * *", "UTC", "a-task"));
        Profile b = profile("b", schedule("daily", "0 0 9 * * *", "UTC", "b-task"));

        Lock lockOfA = scheduler.lockFor("a", "daily");
        lockOfA.lock();
        try {
            scheduler.runOnce(b, b.getSchedules().get(0));

            // 锁键含 profileName：A 占着锁不影响 B
            verify(agentService).process(any(Session.class), org.mockito.ArgumentMatchers.eq("b-task"));
        } finally {
            lockOfA.unlock();
        }
    }

    @Test
    void 上一次结束后下一次触发正常进入() {
        Profile profile = profile("daily", schedule("brief", "0 0 9 * * *", "UTC", "hi"));

        scheduler.runOnce(profile, profile.getSchedules().get(0));
        scheduler.runOnce(profile, profile.getSchedules().get(0));

        verify(agentService, times(2)).process(any(Session.class), anyString());
    }

    // ---------- US3：失败隔离 ----------

    @Test
    void 任务抛异常_不外抛且锁必须被释放() {
        Profile profile = profile("daily", schedule("brief", "0 0 9 * * *", "UTC", "hi"));
        when(agentService.process(any(Session.class), anyString()))
                .thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> scheduler.runOnce(profile, profile.getSchedules().get(0)))
                .doesNotThrowAnyException();

        // 二进宫：还能进来才证明 finally 的 unlock 真的执行了（只断言"不抛异常"抓不到漏 unlock）
        scheduler.runOnce(profile, profile.getSchedules().get(0));

        verify(agentService, times(2)).process(any(Session.class), anyString());
    }

    // ---------- US4：配置校验 ----------

    @Test
    void 非法配置只跳过该条_合法条照常注册() {
        Profile.ScheduleConfig missingId = schedule(null, "0 0 9 * * *", "UTC", "hi");
        Profile.ScheduleConfig blankCron = schedule("blank-cron", "  ", "UTC", "hi");
        Profile.ScheduleConfig badCron = schedule("bad-cron", "not-a-cron", "UTC", "hi");
        Profile.ScheduleConfig badZone = schedule("bad-zone", "0 0 9 * * *", "Mars/Base", "hi");
        Profile.ScheduleConfig noMessage = schedule("no-message", "0 0 9 * * *", "UTC", null);
        Profile.ScheduleConfig valid = schedule("valid", "0 0 9 * * *", "UTC", "正常的那条");

        profileRegistry.registerProfile("mixed",
                profile("mixed", missingId, blankCron, badCron, badZone, noMessage, valid));

        assertThat(scheduler.registerAll()).isEqualTo(1);
        assertThat(scheduler.registeredTasks()).containsExactly("mixed#valid");
    }

    @Test
    void Profile内重复id只注册第一条() {
        profileRegistry.registerProfile("dup", profile("dup",
                schedule("daily", "0 0 9 * * *", "UTC", "第一条"),
                schedule("daily", "0 0 18 * * *", "UTC", "第二条")));

        assertThat(scheduler.registerAll()).isEqualTo(1);
        assertThat(scheduler.registeredTasks()).containsExactly("dup#daily");
    }

    @Test
    void runOnce遇到无id或无消息时不调用AgentService且不抛异常() {
        Profile.ScheduleConfig noId = schedule(null, "0 0 9 * * *", "UTC", "hi");
        Profile.ScheduleConfig noMessage = schedule("no-message", "0 0 9 * * *", "UTC", null);

        assertThatCode(() -> scheduler.runOnce(profile("daily", noId), noId))
                .doesNotThrowAnyException();
        assertThatCode(() -> scheduler.runOnce(profile("daily", noMessage), noMessage))
                .doesNotThrowAnyException();

        verify(agentService, never()).process(any(Session.class), anyString());
    }

    // ---------- 生命周期 ----------

    @Test
    void 关闭时取消全部已注册任务() {
        profileRegistry.registerProfile("daily",
                profile("daily", schedule("brief", "0 0 9 * * *", "UTC", "hi")));
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        scheduler.registerAll();
        scheduler.destroy();

        verify(future).cancel(false);
    }
}
