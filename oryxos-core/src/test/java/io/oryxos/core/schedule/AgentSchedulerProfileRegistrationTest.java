package io.oryxos.core.schedule;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 运行时登记 / 注销：注销必须把该 Profile 的定时任务真的取消，且同名可以重新登记。
 */
class AgentSchedulerProfileRegistrationTest {

    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final AgentService agentService = mock(AgentService.class);
    private final SessionManager sessionManager = new SessionManager();
    private final ProfileRegistry registry = new ProfileRegistry();

    private AgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AgentScheduler(taskScheduler, registry, sessionManager, agentService);
        doReturn(mock(ScheduledFuture.class))
                .when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    private static Profile.ScheduleConfig schedule(String id, String cron) {
        Profile.ScheduleConfig config = new Profile.ScheduleConfig();
        config.setId(id);
        config.setCron(cron);
        config.setTimezone("Asia/Shanghai");
        config.setMessage("干活");
        return config;
    }

    private static Profile profile(String name, Profile.ScheduleConfig... schedules) {
        Profile profile = new Profile();
        profile.setName(name);
        profile.setSchedules(List.of(schedules));
        return profile;
    }

    @Test
    void 只注册传入Profile的任务() {
        Profile daily = profile("daily", schedule("a", "0 0 9 * * *"), schedule("b", "0 0 18 * * *"));

        int registered = scheduler.registerProfile(daily);

        assertThat(registered).isEqualTo(2);
        assertThat(scheduler.registeredTasksOf("daily")).containsExactlyInAnyOrder("daily#a", "daily#b");
        assertThat(scheduler.registeredTasksOf("other")).isEmpty();
    }

    @Test
    void 没有定时声明的Profile不注册也不报错() {
        assertThat(scheduler.registerProfile(profile("plain"))).isZero();
        assertThat(scheduler.registeredTasks()).isEmpty();
    }

    @Test
    void 注销会取消该Profile的全部定时任务() {
        Profile daily = profile("daily", schedule("a", "0 0 9 * * *"), schedule("b", "0 0 18 * * *"));
        Profile other = profile("other", schedule("c", "0 0 9 * * *"));
        ScheduledFuture<?> dailyA = mock(ScheduledFuture.class);
        ScheduledFuture<?> dailyB = mock(ScheduledFuture.class);
        ScheduledFuture<?> otherC = mock(ScheduledFuture.class);
        doReturn(dailyA, dailyB, otherC)
                .when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        scheduler.registerProfile(daily);
        scheduler.registerProfile(other);

        int cancelled = scheduler.unregisterProfile("daily");

        assertThat(cancelled).isEqualTo(2);
        verify(dailyA).cancel(false);
        verify(dailyB).cancel(false);
        verify(otherC, never()).cancel(false);
        assertThat(scheduler.registeredTasksOf("daily")).isEmpty();
        assertThat(scheduler.registeredTasksOf("other")).containsExactly("other#c");
    }

    @Test
    void 注销不存在的名字是幂等的() {
        assertThat(scheduler.unregisterProfile("ghost")).isZero();
        assertThat(scheduler.unregisterProfile("")).isZero();
    }

    @Test
    void 注销后同名可以重新登记() {
        Profile daily = profile("daily", schedule("a", "0 0 9 * * *"));
        scheduler.registerProfile(daily);
        scheduler.unregisterProfile("daily");

        int again = scheduler.registerProfile(profile("daily", schedule("a", "0 0 9 * * *")));

        assertThat(again).isEqualTo(1);
        assertThat(scheduler.registeredTasksOf("daily")).containsExactly("daily#a");
    }
}
